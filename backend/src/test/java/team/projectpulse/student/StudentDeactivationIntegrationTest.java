package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.system.StatusCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-STU-deactivate-student: PATCH /students/{studentId}/deactivation, its main flow for both authorities and
 * extensions 6a, 6b and 6c. Seed data: John (id 4) is on Team1 in course section 2 of course 1; Bingyang owns
 * course 1 and Bill is assigned to course sections 1 and 2; Tim owns course 3 and teaches its course section 3.
 */
@DisplayName("Integration tests for deactivating a student")
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {

    private static final int JOHN = 4;

    private static final int NO_SUCH_ID = 999999;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JsonMapper jsonMapper;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String adminBingyangToken;

    String instructorBillToken;

    String adminTimToken;

    String studentJohnToken;

    String studentEricToken;


    @BeforeEach
    void setUp() throws Exception {
        this.adminBingyangToken = login("b.wei@abc.edu");
        this.instructorBillToken = login("b.gates@abc.edu");
        this.adminTimToken = login("t.cook@abc.edu");
        this.studentJohnToken = login("j.smith@abc.edu");
        this.studentEricToken = login("e.hudson@abc.edu");
    }

    private String login(String email) throws Exception {
        MvcResult mvcResult = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        JSONObject json = new JSONObject(mvcResult.getResponse().getContentAsString());
        return "Bearer " + json.getJSONObject("data").getString("token");
    }

    private ResultActions deactivate(int studentId, String token) throws Exception {
        return this.mockMvc.perform(patch(this.baseUrl + "/students/" + studentId + "/deactivation").accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token));
    }

    private void unassignFromSection2(String email) {
        Section section = this.entityManager.find(Section.class, 2);
        Instructor instructor = this.entityManager.createQuery("select i from Instructor i where i.email = :email", Instructor.class)
                .setParameter("email", email).getSingleResult();
        section.removeInstructor(instructor);
        this.entityManager.flush();
    }

    private Student reloadJohn() {
        this.entityManager.flush();
        this.entityManager.clear();
        return this.entityManager.find(Student.class, JOHN);
    }

    private long count(String jpql) {
        return this.entityManager.createQuery(jpql, Long.class).setParameter("id", JOHN).getSingleResult();
    }

    private void assertJohnIsStillActive() {
        assertThat(reloadJohn().isEnabled()).isTrue();
    }

    // Main success scenario

    @Test
    void testAssignedInstructorDeactivatesAStudent() throws Exception {
        long activities = count("select count(a) from Activity a where a.student.id = :id");
        long evaluations = count("select count(e) from PeerEvaluation e where e.evaluator.id = :id or e.evaluatee.id = :id");

        deactivate(JOHN, this.instructorBillToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.message").value("Deactivate student successfully"))
                .andExpect(jsonPath("$.data.id").value(JOHN))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.teamName").value("Team1"))
                .andExpect(jsonPath("$.data.sectionId").value(2));

        // She stays in her course section and on her team, and her work is kept (BR-student-lifecycle)
        Student john = reloadJohn();
        assertThat(john.isEnabled()).isFalse();
        assertThat(john.getTeam().getTeamName()).isEqualTo("Team1");
        assertThat(john.getSection().getSectionId()).isEqualTo(2);
        assertThat(count("select count(a) from Activity a where a.student.id = :id")).isEqualTo(activities);
        assertThat(count("select count(e) from PeerEvaluation e where e.evaluator.id = :id or e.evaluatee.id = :id")).isEqualTo(evaluations);

        // And the instructor can still read her peer evaluations
        this.mockMvc.perform(get(this.baseUrl + "/evaluations/students/" + JOHN + "/week/2023-W31/details").accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, this.instructorBillToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true));
    }

    @Test
    void testUnassignedCourseAdminOfHerCourseDeactivatesAStudent() throws Exception {
        unassignFromSection2("b.wei@abc.edu");

        deactivate(JOHN, this.adminBingyangToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertThat(reloadJohn().isEnabled()).isFalse();
    }

    // 6a

    @Test
    void testDeactivatingAnAlreadyDeactivatedStudentIsAConflict() throws Exception {
        deactivate(JOHN, this.instructorBillToken).andExpect(status().isOk());

        deactivate(JOHN, this.adminBingyangToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.CONFLICT))
                .andExpect(jsonPath("$.message").value("This student is already deactivated."));
        assertThat(reloadJohn().isEnabled()).isFalse();
    }

    // 6b

    @Test
    void testAnInstructorOfAnotherCourseSectionIsRefused() throws Exception {
        // Bill keeps course section 1 of the same course, but not John's
        unassignFromSection2("b.gates@abc.edu");

        deactivate(JOHN, this.instructorBillToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
        assertJohnIsStillActive();
    }

    @Test
    void testACourseAdminOfAnotherCourseIsRefused() throws Exception {
        deactivate(JOHN, this.adminTimToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
        assertJohnIsStillActive();
    }

    @Test
    void testATeammateIsRefused() throws Exception {
        deactivate(JOHN, this.studentEricToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
        assertJohnIsStillActive();
    }

    @Test
    void testTheStudentHerselfIsRefused() throws Exception {
        deactivate(JOHN, this.studentJohnToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
        assertJohnIsStillActive();
    }

    // 6c

    @Test
    void testANonexistentStudentIsRefusedExactlyLikeAForbiddenOne() throws Exception {
        String refusalOfAnExistingStudent = deactivate(JOHN, this.adminTimToken)
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        String refusalOfANonexistentStudent = deactivate(NO_SUCH_ID, this.adminTimToken)
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        JsonNode nonexistent = this.jsonMapper.readTree(refusalOfANonexistentStudent);
        assertThat(nonexistent).isEqualTo(this.jsonMapper.readTree(refusalOfAnExistingStudent));
        assertThat(nonexistent.has("flag") && nonexistent.has("code") && nonexistent.has("message") && nonexistent.has("data")).isTrue();
    }

}
