// BR-evaluatee-active, through UC-STU-deactivate-student: the peer evaluation form leaves out a deactivated teammate on a
// first submission and keeps a saved evaluation of one read-only on an edit. The API calls are stubbed; the backend's
// own tests cover the refusals. Run against the dev stack (backend + `npm run dev`) with `npm run cy:open`.

const teammates = [
  { id: 4, firstName: 'John', lastName: 'Smith', enabled: true, teamId: 1 },
  { id: 5, firstName: 'Eric', lastName: 'Hudson', enabled: false, teamId: 1 }, // Deactivated
  { id: 6, firstName: 'Jerry', lastName: 'Moon', enabled: true, teamId: 1 }
]

const criteria = [
  { criterionId: 1, criterion: 'Quality of work', description: 'Quality', maxScore: 10 }
]

const saved = (evaluationId, evaluateeId, evaluateeName) => ({
  evaluationId,
  week: 'any',
  evaluatorId: 4,
  evaluatorName: 'John Smith',
  evaluateeId,
  evaluateeName,
  ratings: [
    { ratingId: evaluationId, criterionId: 1, criterion: 'Quality of work', actualScore: 8 }
  ],
  totalScore: 8,
  publicComment: `About ${evaluateeName}`,
  privateComment: ''
})

const ok = (data) => ({ flag: true, code: 200, message: 'ok', data })

describe('Peer evaluations of a deactivated teammate', () => {
  beforeEach(() => {
    cy.login('j.smith@abc.edu', '123456')
    cy.intercept('GET', '**/students/teams/*', { body: ok(teammates) })
    cy.intercept('GET', '**/sections/*', { body: ok({ sectionId: 2, rubricId: 1 }) })
    cy.intercept('GET', '**/rubrics/*', { body: ok({ rubricId: 1, criteria }) })
    cy.intercept('POST', '**/evaluations/weeks/*/receipt', { body: ok(null) })
    cy.intercept('POST', '**/evaluations', (req) =>
      req.reply({ body: ok({ ...req.body, evaluationId: req.body.evaluateeId }) })
    ).as('submit')
    cy.intercept('PUT', '**/evaluations/*', (req) => req.reply({ body: ok(req.body) })).as('update')
  })

  it('does not offer a deactivated teammate on a first submission', () => {
    cy.intercept('GET', '**/evaluations/evaluators/4/week/*', { body: ok([]) }).as('saved')
    cy.visit('/evaluations/submit-evaluations')
    cy.wait('@saved')

    cy.get('.el-table__body-wrapper')
      .should('contain', 'John Smith')
      .and('contain', 'Jerry Moon')
      .and('not.contain', 'Eric Hudson')
    cy.contains('button', 'Submit').click()

    cy.wait('@submit')
    cy.wait('@submit')
    cy.get('@submit.all').should('have.length', 2)
    cy.get('@submit.all').then((calls) => {
      expect(calls.map((call) => call.request.body.evaluateeId)).to.have.members([4, 6])
    })
  })

  it('keeps a saved evaluation of a now-deactivated teammate read-only and does not send it again', () => {
    cy.intercept('GET', '**/evaluations/evaluators/4/week/*', {
      body: ok([saved(1, 4, 'John Smith'), saved(2, 5, 'Eric Hudson'), saved(3, 6, 'Jerry Moon')])
    }).as('saved')
    cy.visit('/evaluations/submit-evaluations')
    cy.wait('@saved')

    cy.contains('.el-table__row', 'Eric Hudson').within(() => {
      cy.get('[data-cy=evaluatee-deactivated]').should('contain', 'Deactivated')
      cy.get('textarea').should('be.disabled')
      cy.get('.el-input-number').should('have.class', 'is-disabled')
    })
    cy.contains('.el-table__row', 'Jerry Moon')
      .find('[data-cy=evaluatee-deactivated]')
      .should('not.exist')

    cy.contains('button', 'Update').click()

    cy.get('.el-message--success').should('contain', 'Peer evaluations updated successfully')
    cy.get('@update.all').should('have.length', 2)
    cy.get('@update.all').then((calls) => {
      expect(calls.map((call) => call.request.url.split('/').pop())).to.have.members(['1', '3'])
    })
    cy.contains('.el-table__row', 'Eric Hudson').should('exist') // Still shown after the update
  })
})
