// UC-STU-deactivate-student: the deactivate action in the student edit dialog. The student API calls are stubbed, so
// these tests check what the dialog shows and sends; the backend's own tests cover who may deactivate and what changes.
// Run against the dev stack (backend + `npm run dev`) with `npm run cy:open`.

const john = {
  id: 4,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  roles: 'student',
  sectionId: 2,
  sectionName: '2023-2024',
  teamId: 1,
  teamName: 'Team1'
}

const found = (student) => ({
  flag: true,
  code: 200,
  message: 'Find student successfully',
  data: student
})

const page = (students) => ({
  flag: true,
  code: 200,
  message: 'Find students successfully',
  data: { content: students, number: 0, size: 50, totalElements: students.length }
})

describe('Deactivate a student', () => {
  beforeEach(() => {
    cy.login('b.gates@abc.edu', '123456')
    cy.intercept('PATCH', '**/students/4/deactivation', {
      body: {
        flag: true,
        code: 200,
        message: 'Deactivate student successfully',
        data: { ...john, enabled: false }
      }
    }).as('deactivate')
  })

  function openJohn(student = john) {
    cy.intercept('POST', '**/students/search*', { body: page([student]) }).as('search')
    cy.intercept('GET', '**/students/4', { body: found(student) }).as('findJohn')
    cy.visit('/students')
    cy.wait('@search')
    cy.get('.el-table__row').first().find('button').click()
    cy.wait('@findJohn')
  }

  it('shows an active student as Active with a Deactivate action and no Status switch', () => {
    openJohn()

    cy.get('[data-cy=student-status-active]').should('contain', 'Active')
    cy.get('[data-cy=deactivate-student]').should('be.visible')
    cy.get('[data-cy=student-status-switch]').should('not.exist')
  })

  it('does not offer Deactivate for a student who is already deactivated (3a)', () => {
    openJohn({ ...john, enabled: false })

    cy.get('[data-cy=deactivate-student]').should('not.exist')
    cy.get('[data-cy=student-status-switch]').should('exist')
  })

  it('warns about the consequences before deactivating (step 4)', () => {
    openJohn()
    cy.get('[data-cy=deactivate-student]').click()

    cy.get('.el-message-box').should('be.visible')
    cy.get('.el-message-box__title').should('contain', 'Deactivate John Smith?')
    cy.get('.el-message-box__message')
      .should(
        'contain',
        'John will no longer be able to log in, and a session already open ends at the next action.'
      )
      .and(
        'contain',
        'will no longer owe weekly activity reports or peer evaluations and will get no reminders.'
      )
      .and('contain', 'can no longer submit or change a peer evaluation of John.')
      .and('contain', 'all weekly activity reports and peer evaluations are kept.')
      .and('contain', 'You can reactivate John later.')
    cy.get('.el-message-box').contains('button', 'Deactivate').should('exist')
    cy.get('.el-message-box').contains('button', 'Cancel').should('exist')
  })

  it('sends nothing and stays on the student when the instructor cancels (5a)', () => {
    openJohn()
    cy.get('[data-cy=deactivate-student]').click()
    cy.get('.el-message-box').contains('button', 'Cancel').click()

    cy.get('.el-message-box').should('not.exist')
    cy.get('[data-cy=deactivate-student]').should('be.visible') // The dialog is still on John
    cy.get('@deactivate.all').should('have.length', 0)
  })

  it('deactivates on confirm, says so, closes the dialog and reloads the list (step 6)', () => {
    openJohn()
    // From here on the list finds her disabled
    cy.intercept('POST', '**/students/search*', { body: page([{ ...john, enabled: false }]) }).as(
      'reload'
    )
    cy.get('@search.all').then((before) => {
      cy.get('[data-cy=deactivate-student]').click()
      cy.get('.el-message-box').contains('button', 'Deactivate').click()

      cy.wait('@deactivate')
      cy.get('.el-message--success').should('contain', 'John Smith has been deactivated.')
      cy.get('[data-cy=deactivate-student]').should('not.exist') // The dialog has closed
      cy.wait('@reload') // A second search request
      cy.get('@search.all').should('have.length', before.length) // The first stub was not asked again
    })
    cy.get('@deactivate.all').should('have.length', 1)
    cy.get('.el-table__row').first().should('contain', 'Disabled')
  })

  it('shows the refusal once and reloads her when she was deactivated meanwhile (6a)', () => {
    openJohn()
    cy.intercept('PATCH', '**/students/4/deactivation', {
      statusCode: 409,
      body: { flag: false, code: 409, message: 'This student is already deactivated.' }
    }).as('conflict')
    // Her reload now finds her deactivated
    cy.intercept('GET', '**/students/4', { body: found({ ...john, enabled: false }) }).as(
      'reloadJohn'
    )

    cy.get('[data-cy=deactivate-student]').click()
    cy.get('.el-message-box').contains('button', 'Deactivate').click()

    cy.wait('@conflict')
    cy.wait('@reloadJohn')
    cy.get('.el-message--error')
      .should('have.length', 1)
      .and('contain', 'This student is already deactivated.')
    cy.get('[data-cy=deactivate-student]').should('not.exist')
    cy.get('[data-cy=student-status-switch]').should('exist')
  })
})
