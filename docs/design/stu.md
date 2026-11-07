# Students (STU) Design

> Realizes: UC-STU-deactivate-student, FR-SEC-active-account
> Depends on: BR-student-lifecycle, BR-evaluatee-active, BR-evaluation-editable-until-close, BR-submission-owed, BR-section-scoped-access, BR-role-based-access
> See: [use cases](../requirements/use-cases.md#student), [SRS security requirements](../requirements/software-requirements-specification.md#security-and-authorization-requirements), [architectural-design.md](architectural-design.md#security--compliance) (Crosscutting Concepts: authentication, authorization)
> Designed against `main` at `59f0267` (2026-11-06).

## Overview

This area manages a student's account in a course section. Its first designed use case, UC-STU-deactivate-student, replaces the Status switch in the student edit dialog with a dedicated deactivate action. The action has its own route and authority rule, a warning that states the consequences, and a confirmation. It touches three slices of the REST API container: `student` (the action), `security` (who may call it, and refusing a deactivated account on every request), and `evaluation` (BR-evaluatee-active). Team membership is untouched (BR-student-lifecycle).

Before this design, deactivation was a field of the generic `PUT /students/{studentId}`. That route's rule admitted the student herself and refused a course admin not assigned to her course section. Nothing warned the instructor. A deactivated student's JWT kept working for up to two hours, because `MyUserPrincipal.isEnabled()` was checked only at login. And `EvaluationService` still accepted evaluations of her.

## Components & classes

```mermaid
classDiagram
  direction LR
  class StudentController {
    <<reused, student>>
    +deactivateStudent(studentId) Result NEW
  }
  class StudentService {
    <<reused, student>>
    +deactivateStudent(studentId) Student NEW
    +updateStudent(studentId, update) Student CHANGED
  }
  class StudentSecurityService {
    <<reused, student>>
    +isCurrentUserInstructorOfStudentSection(studentId) boolean
    +isCurrentUserAdminOfStudentCourse(studentId) boolean NEW
  }
  class StudentAlreadyDeactivatedException {
    <<new, student>>
  }
  class StudentInstructorAuthorizationManager {
    <<reused, scope widened>>
  }
  class StudentCourseOwnershipAuthorizationManager {
    <<new, security>>
  }
  class ActiveAccountJwtAuthenticationConverter {
    <<new, security>>
    +convert(jwt) AbstractAuthenticationToken
  }
  class SecurityConfiguration {
    <<changed, security>>
    route rule for PATCH deactivation
    jwt.jwtAuthenticationConverter(...)
  }
  class ExceptionHandlerAdvice {
    <<changed, system>>
    +handleStudentAlreadyDeactivated() 409
  }
  class EvaluationService {
    <<reused, evaluation>>
    +addPeerEvaluation() CHANGED
    +updatePeerEvaluation() CHANGED
    +generateWeeklyPeerEvaluationReportForSection() CHANGED
  }
  class UserRepository {
    <<reused, user>>
  }
  StudentController --> StudentService
  StudentService ..> StudentAlreadyDeactivatedException : throws (6a)
  ExceptionHandlerAdvice ..> StudentAlreadyDeactivatedException : maps
  SecurityConfiguration --> StudentInstructorAuthorizationManager
  SecurityConfiguration --> StudentCourseOwnershipAuthorizationManager
  SecurityConfiguration --> ActiveAccountJwtAuthenticationConverter
  StudentInstructorAuthorizationManager --> StudentSecurityService
  StudentCourseOwnershipAuthorizationManager --> StudentSecurityService
  ActiveAccountJwtAuthenticationConverter --> UserRepository
```

**Backend**
- [`student/StudentController`](../../backend/src/main/java/team/projectpulse/student/StudentController.java), [`StudentService`](../../backend/src/main/java/team/projectpulse/student/StudentService.java), [`StudentSecurityService`](../../backend/src/main/java/team/projectpulse/student/StudentSecurityService.java): one new method each.
- `student/StudentAlreadyDeactivatedException`: new.
- [`system/exception/ExceptionHandlerAdvice`](../../backend/src/main/java/team/projectpulse/system/exception/ExceptionHandlerAdvice.java): maps that exception to `409 CONFLICT`.
- `security/authorizationmanagers/StudentCourseOwnershipAuthorizationManager`: new. It mirrors [`SectionOwnershipAuthorizationManager`](../../backend/src/main/java/team/projectpulse/security/authorizationmanagers/SectionOwnershipAuthorizationManager.java), resolving the course through the student's course section.
- [`StudentInstructorAuthorizationManager`](../../backend/src/main/java/team/projectpulse/security/authorizationmanagers/StudentInstructorAuthorizationManager.java): reused, with its scope widened. It guarded reading a student's evaluation details and now also guards this write route. Its Javadoc is updated to say so; the check is unchanged.
- `security/ActiveAccountJwtAuthenticationConverter`: new. It wraps the existing `JwtAuthenticationConverter` bean, so authorities are unchanged. It loads `PeerEvaluationUser` by the `userId` claim and throws `DisabledException` when the user is disabled or no longer exists.
- [`security/SecurityConfiguration`](../../backend/src/main/java/team/projectpulse/security/SecurityConfiguration.java):
  - The route rule goes before `denyAll()`.
  - The converter is set **explicitly**: `.jwt(jwt -> jwt.jwtAuthenticationConverter(activeAccountConverter))`. Today `.jwt(Customizer.withDefaults())` picks up the `JwtAuthenticationConverter` bean implicitly, and it would not pick up a converter of another type.
- [`evaluation/EvaluationService`](../../backend/src/main/java/team/projectpulse/evaluation/EvaluationService.java): three changes; see *Evaluations of a deactivated student*.

**Frontend**
- [`apis/student/index.ts`](../../frontend/src/apis/student/index.ts): new `deactivateStudent(studentId)`.
- [`pages/students/EditStudentForm.vue`](../../frontend/src/pages/students/EditStudentForm.vue), the dialog that realizes UC-STU-view-student (step 2):
  - It gets a **Deactivate** button, shown only while the student is active (3a).
  - The button opens `ElMessageBox.confirm` with the step-4 consequences, taken from the use case's Associated Information.
  - The Status switch no longer deactivates (see *PUT no longer deactivates*).
- [`pages/students/Students.vue`](../../frontend/src/pages/students/Students.vue): reloads the list after the dialog closes.
- [`pages/evaluations/SubmitTeamsEvaluations.vue`](../../frontend/src/pages/evaluations/SubmitTeamsEvaluations.vue): both branches of `prepareStartingPeerEvaluations` change.
  - **First submission** (nothing saved this week): the form is built from `findStudentsByTeamId`, and a teammate with `enabled: false` is left out.
  - **Edit** (evaluations already saved): the form loads the saved `PeerEvaluationDto`s, which carry no `enabled` field.
    - It finds the deactivated teammate by matching each `evaluateeId` against the teammate list that `loadStudents` already loaded (`StudentDto.enabled`).
    - An evaluation of a deactivated teammate is shown read-only, greyed out, with a "deactivated" tag, so the evaluator still sees what she wrote.
    - `updatePeerEvaluations` sends `PUT`s only for evaluations of active teammates. This is not a courtesy. Without it, the `400` for the deactivated teammate rejects `Promise.all`, `isLoading` stays on, and the student sees a failure although her other edits were saved.

## Sequence

### UC-STU-deactivate-student: the deactivate action

```mermaid
sequenceDiagram
  actor I as Instructor / course admin
  participant F as EditStudentForm.vue
  participant SEC as Spring Security
  participant C as StudentController
  participant S as StudentService
  I->>F: open the student (steps 1–2, findStudentById)
  I->>F: Deactivate (step 3; not shown for a disabled student, 3a)
  F->>I: warning listing the consequences, asks to confirm (step 4)
  alt cancel (5a)
    I->>F: Cancel: no request; dialog stays on the student
  else confirm (step 5)
    F->>SEC: PATCH /api/v1/students/{studentId}/deactivation
    SEC->>SEC: active-account check, then anyOf(instructor of her course section, course admin of her course), else 403 (6b, 6c)
    SEC->>C: deactivateStudent(studentId)
    C->>S: deactivateStudent(studentId)
    alt already disabled (6a, e.g. another instructor deactivated her first)
      S-->>C: StudentAlreadyDeactivatedException
      C-->>F: 409 "This student is already deactivated."
      F->>I: shows the server's message
      F->>F: reloads the student (findStudentById); Deactivate no longer shown (3a)
    else active
      S->>S: setEnabled(false), save
      C-->>F: 200 StudentDto (enabled false)
      F->>I: "student deactivated" message (step 6); dialog closes; Students.vue reloads the list
    end
  end
```

### FR-SEC-active-account: her existing session

```mermaid
sequenceDiagram
  participant SPA as Her browser (valid JWT)
  participant BF as BearerTokenAuthenticationFilter
  participant Conv as ActiveAccountJwtAuthenticationConverter
  participant R as UserRepository
  participant EP as CustomBearerTokenAuthenticationEntryPoint
  participant ADV as ExceptionHandlerAdvice
  SPA->>BF: any /api/v1 request, Bearer JWT
  BF->>BF: JwtDecoder verifies signature and expiry
  BF->>Conv: convert(jwt)
  Conv->>R: findById(userId claim)
  R-->>Conv: enabled false (or no row)
  Conv-->>BF: throws DisabledException
  BF->>EP: commence(request, response, DisabledException)
  EP->>ADV: handlerExceptionResolver.resolveException(...)
  ADV-->>SPA: handleAccountStatusException: 401 "User account is abnormal."
  SPA->>SPA: request.ts on 401: clear token, go to login
```

## API contract

| Endpoint or job | Caller (who may) | Request | Success | Errors, by extension |
|---|---|---|---|---|
| `PATCH /api/v1/students/{studentId}/deactivation` (new) | An instructor assigned to the student's course section, or the course admin of her course (BR-student-lifecycle): `AuthorizationManagers.anyOf(studentInstructorAuthorizationManager, studentCourseOwnershipAuthorizationManager)`, before the `denyAll()` catch-all | No body | `200`, `Result` carrying the updated `StudentDto` (`enabled: false`) | 6a: `409 CONFLICT`, "This student is already deactivated." 6b (an instructor of another course section, a course admin of another course, any student including herself): `403`. 6c (no such student): `403`, with a body identical to 6b's, because the check fails closed |
| `PUT /api/v1/students/{studentId}` (changed) | Unchanged | Unchanged. Every caller keeps sending the student's current `enabled` | Unchanged | Instructor branch of `updateStudent` only: `enabled` true to false is refused, `400` `INVALID_ARGUMENT`, "Use the deactivate action." |
| Every authenticated `/api/v1/**` request (changed) | Unchanged | Bearer JWT | Unchanged | Account disabled or deleted: `401` (FR-SEC-active-account) |
| `POST /api/v1/evaluations`, `PUT /api/v1/evaluations/{evaluationId}` (changed) | Unchanged | Unchanged | Unchanged | Evaluatee deactivated: `400` `INVALID_ARGUMENT`, naming the condition (BR-evaluatee-active) |

## Key decisions

**A dedicated route.**
- Chosen: `PATCH /students/{studentId}/deactivation` with its own rule.
- Rejected: reusing `PUT /students/{studentId}`. Its rule (`UserOwnershipAuthorizationManager`) admits the student herself and refuses a course admin not assigned to her course section, both wrong under 6b and BR-student-lifecycle. It also cannot express 6a, and a generic save of the edit form would skip the step-4 warning.

**PUT no longer deactivates.**
- Chosen: the `PUT` refuses `enabled` true to false but still accepts false to true, so reactivation keeps working through the switch until UC-STU-reactivate-student is designed.
- Rejected: removing `enabled` from the `PUT`, which would leave no way to reactivate anyone.
- Rejected: leaving the `PUT` as it is, a second deactivation path with no warning and the wrong authority.
- Two constraints follow:
  - The refusal sits **only in the `ROLE_instructor` branch** of `updateStudent`, the one place `enabled` is applied today. The student's own branch already ignores `enabled`, and `UserInfo.vue` sends it when she saves her profile.
  - `StudentDto.enabled` is a primitive `boolean`, so an instructor `PUT` that leaves it out arrives as `false` and is refused. **Every caller must keep sending the current value.** `EditStudentForm.vue` does today, and its rework must not drop it.
- Rejected: making the field `Boolean` with `null` meaning "unchanged", a wider DTO change for a case no caller hits.

**A deactivated account is refused on every request.**
- Chosen: `ActiveAccountJwtAuthenticationConverter` loads the user by primary key on each authenticated request.
- This is a **trunk change**. It sits in the shared security path and also refuses a deactivated instructor, as FR-SEC-active-account says for both.
- Rejected: accepting the up-to-two-hour window, which breaks FR-SEC-active-account.
- Rejected: a token version or revocation list, which needs a schema change and still a lookup per request.
- Rejected: an `OAuth2TokenValidator`, which reports "invalid token" for what is a disabled account.
- [TD-jwt-revocation](architectural-design.md#risks-and-technical-debt) remains for a leaked token of an *active* account.

**6a is a conflict.**
- Chosen: `409 CONFLICT`. The request conflicts with the student's current state.
- Rejected: `400`, which this codebase uses for malformed input and for refused submissions.
- Rejected: an idempotent `200`, because the use case refuses the request (6a1).

**Course admin authority by composition.**
- Chosen: a new `StudentCourseOwnershipAuthorizationManager` composed with `anyOf`, the same pattern [not.md](not.md#key-decisions) uses for the reminder routes.
- Rejected: widening `isCurrentUserInstructorOfStudentSection` to admit the course admin, which would also open the other routes that use it.

**Evaluations of a deactivated student (BR-evaluatee-active).**
- `addPeerEvaluation` and `updatePeerEvaluation` refuse a deactivated evaluatee with `PeerEvaluationIllegalArgumentException`. That is how the other rule conditions on a submission are refused (BR-team-assignment-required), not as a permission refusal.
- The section report keeps a deactivated student for a week only if she **received** at least one peer evaluation that week. Evaluations she wrote do not count. The report already computes her average from the evaluations she received, so this filter needs no extra query.
- Rejected: storing a deactivation date and listing her for weeks before it. That needs a column, and a single date breaks once she is reactivated.

**The team is untouched.** BR-student-lifecycle keeps her in her course section and on her team, so no team code changes. BR-submission-owed already excludes her through `SubmissionStatusService.findOwedItems`.

## Data model

No delta. Deactivation flips the existing `enabled` column of the user row. Nothing records when it happened (see *Evaluations of a deactivated student*). The entities are as the SRS's [Business Domain Model](../requirements/software-requirements-specification.md#business-domain-model) defines them.

## Reuse & cross-cutting

- Error envelope: `Result`, through `ExceptionHandlerAdvice`. The existing `AccountStatusException` handler produces the `401`, and the new exception the `409`.
- Authorization: `AuthorizationManagers.anyOf`, `StudentInstructorAuthorizationManager`, and the fail-closed `*SecurityService` convention (an unknown id is denied, not thrown).
- Frontend: `request.ts` already clears the token and redirects to login on `401`; `ElMessageBox.confirm` for the warning.
- BR-submission-owed: unchanged; `SubmissionStatusService.findOwedItems` already skips a deactivated student, both as someone who owes and as a teammate to be evaluated.

## Tests

| Use case, flow | Level | Asserts |
|---|---|---|
| UC-STU-deactivate-student main, assigned instructor | integration | `PATCH` returns `200` with `enabled: false`; her team and course section are unchanged; her activities and peer evaluations are still readable by the instructor |
| UC-STU-deactivate-student main, course admin | integration | The course admin of her course, not assigned to her course section, gets `200` |
| UC-STU-deactivate-student step 4, 3a, 5a | E2E (Cypress, API stubbed) | The warning shows the consequences; no Deactivate button for a disabled student; Cancel sends no `PATCH` and the dialog stays on the student |
| UC-STU-deactivate-student step 6, UI | E2E (Cypress, API stubbed) | Confirm sends one `PATCH`; on `200` the "student deactivated" message shows, the dialog closes, and the list is reloaded (a second search request) showing her as disabled |
| UC-STU-deactivate-student 6a | integration | `PATCH` for an already-deactivated student returns `409`, the message names the condition, nothing changes |
| UC-STU-deactivate-student 6a, UI | E2E (Cypress, API stubbed) | `PATCH` answered `409`: the server's message shows, the student is reloaded (`findStudentById`), and the Deactivate button is gone |
| UC-STU-deactivate-student 6b | integration | An instructor of another course section, a course admin of another course, a teammate, and the student herself each get `403` with the `Result` envelope; nothing changes |
| UC-STU-deactivate-student 6c | integration | `PATCH` for a nonexistent id returns `403`, not `404`, with a body (`flag`, `code`, `message`, `data`) identical to a 6b refusal of an existing student |
| FR-SEC-active-account, existing session | integration | Her JWT issued before deactivation gets `401` on her next request; logging in with her password gets `401` |
| FR-SEC-active-account, deleted user | integration | A JWT whose user has since been deleted gets `401` |
| FR-SEC-active-account, instructor | integration | A disabled instructor's JWT gets `401` |
| FR-SEC-active-account, active user | integration | An active user's requests are unaffected (regression guard on the trunk change) |
| PUT no longer deactivates | integration | An instructor's `PUT` with `enabled: false` for an active student returns `400` and nothing changes; a student's `PUT` of her own profile (sending `enabled: true`) still succeeds |
| Regression guard, UC-STU-reactivate-student (not this use case) | integration | An instructor's `PUT` with `enabled: true` for a disabled student still reactivates her |
| BR-evaluatee-active, submit | unit and integration | `POST /evaluations` of a deactivated teammate returns `400` naming the condition, nothing saved; a separate `POST` by the same evaluator for an active teammate, same week, returns `200` |
| BR-evaluatee-active, edit | unit and integration | `PUT /evaluations/{evaluationId}` of an existing evaluation of a now-deactivated teammate, window open, returns `400` naming the condition, stored evaluation unchanged; a `PUT` of her evaluation of an active teammate, same week, succeeds |
| BR-evaluatee-active, report | unit | For one week: a deactivated student who received at least one evaluation is listed; one who received none is not; one who wrote evaluations that week but received none is not; an active student who received none is still listed |
| Evaluation form, first submission | E2E (Cypress, API stubbed) | A deactivated teammate is not offered; the submitted batch has no evaluation of her |
| Evaluation form, edit | E2E (Cypress, API stubbed) | A saved evaluation of a now-deactivated teammate is read-only with the "deactivated" tag; Update sends no `PUT` for it; the other `PUT`s succeed and the success message shows |
| BR-submission-owed | — | Already covered by `SubmissionStatusServiceIntegrationTest` (deactivated student); cited, no new test |

## Open questions / risks

- **One query per request.** The active-account check adds a primary-key lookup to every authenticated request. Negligible at course scale; not measured.
- **Locks she holds.** A RAM edit lock held by a student when she is deactivated stays held until it expires (BR-lock-expiry). Accepted.
- **Fewer raters in her last week.** Teammates who had not yet rated her stop owing it at deactivation (BR-submission-owed), so her average for that week may come from fewer raters. Accepted.
- **Unsaved work.** Anything she has not saved when her next request gets the `401` is lost. Accepted.
- **Reactivation.** Until UC-STU-reactivate-student is designed, reactivation stays on the edit form's switch and the `PUT`, with no warning and the `PUT`'s authority rule.
