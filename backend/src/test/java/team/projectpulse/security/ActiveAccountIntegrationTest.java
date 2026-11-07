package team.projectpulse.security;

import jakarta.persistence.EntityManager;
import org.json.JSONObject;
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
import team.projectpulse.student.Student;
import team.projectpulse.student.StudentRepository;
import team.projectpulse.system.StatusCode;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-SEC-active-account: a token issued before its account was deactivated or deleted is refused on the next
 * request, for a student and an instructor alike, while an active account's requests are unaffected.
 */
@DisplayName("Integration tests for the per-request active-account check")
class ActiveAccountIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    StudentRepository studentRepository;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    String studentJohnToken;

    String instructorBillToken;


    @BeforeEach
    void setUp() throws Exception {
        this.studentJohnToken = login("j.smith@abc.edu");
        this.instructorBillToken = login("b.gates@abc.edu");
    }

    private String login(String email) throws Exception {
        MvcResult mvcResult = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        JSONObject json = new JSONObject(mvcResult.getResponse().getContentAsString());
        return "Bearer " + json.getJSONObject("data").getString("token");
    }

    private ResultActions getStudent(int studentId, String token) throws Exception {
        return this.mockMvc.perform(get(this.baseUrl + "/students/" + studentId).accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token));
    }

    private Student findStudent(String email) {
        return this.entityManager.createQuery("select s from Student s where s.email = :email", Student.class)
                .setParameter("email", email).getSingleResult();
    }

    @Test
    void testAnActiveAccountIsUnaffected() throws Exception {
        getStudent(4, this.studentJohnToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS));
    }

    @Test
    void testADeactivatedStudentsExistingTokenIsRefusedOnHerNextRequest() throws Exception {
        // Given: John logged in while active, then is deactivated
        Student john = findStudent("j.smith@abc.edu");
        john.setEnabled(false);
        this.entityManager.flush();

        // When and then: the token he already holds is refused
        getStudent(john.getId(), this.studentJohnToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED))
                .andExpect(jsonPath("$.message").value("User account is abnormal."));

        // And he can no longer log in with his password
        this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic("j.smith@abc.edu", "123456")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    void testATokenWhoseUserWasDeletedIsRefused() throws Exception {
        // Given: Tracy (no team, so nothing references her) logs in and is then deleted
        String studentTracyToken = login("t.nicholson@abc.edu");
        Student tracy = findStudent("t.nicholson@abc.edu");
        this.studentRepository.delete(tracy);
        this.entityManager.flush();

        // When and then
        getStudent(tracy.getId(), studentTracyToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

    @Test
    void testADeactivatedInstructorsExistingTokenIsRefused() throws Exception {
        // Given: Bill, who may read John while active, is deactivated
        getStudent(4, this.instructorBillToken).andExpect(status().isOk());
        Instructor bill = this.entityManager.createQuery("select i from Instructor i where i.email = 'b.gates@abc.edu'", Instructor.class).getSingleResult();
        bill.setEnabled(false);
        this.entityManager.flush();

        // When and then
        getStudent(4, this.instructorBillToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.UNAUTHORIZED));
    }

}
