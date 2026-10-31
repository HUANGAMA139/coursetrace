package com.coursetrace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coursetrace.auth.AppUser;
import com.coursetrace.auth.AppUserMapper;
import com.coursetrace.course.Course;
import com.coursetrace.course.CourseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class M1AuthCourseTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserMapper users;
    @Autowired CourseMapper courses;
    @Autowired PasswordEncoder passwords;

    @BeforeEach
    void prepare() {
        courses.delete(Wrappers.emptyWrapper());
        seed("testalice");
        seed("testbob");
    }

    private void seed(String username) {
        if (users.selectCount(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username)) > 0) return;
        var user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwords.encode("TestPassword@123"));
        user.setCreatedAt(LocalDateTime.now());
        users.insert(user);
    }

    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", username).param("password", "TestPassword@123"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long create(MockHttpSession session, String title) throws Exception {
        var body = json.writeValueAsString(Map.of("title", title, "description", "课程简介"));
        var result = mvc.perform(post("/api/courses").session(session).with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    @Test
    void anonymousRequestsReturnJson401() throws Exception {
        mvc.perform(get("/api/courses")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void wrongPasswordDoesNotAuthenticate() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "testalice").param("password", "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void loginCreatesSessionAndLogoutInvalidatesIt() throws Exception {
        var session = login("testalice");
        var context = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(((UserDetails) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("testalice"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isOk());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingCsrfCannotCreateOrLogout() throws Exception {
        var session = login("testalice");
        mvc.perform(post("/api/courses").session(session).contentType("application/json").content("{\"title\":\"攻击请求\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        assertThat(courses.selectCount(Wrappers.emptyWrapper())).isZero();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void courseOwnerComesFromSessionAndInputIsTrimmed() throws Exception {
        var session = login("testalice");
        var bob = users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, "testbob"));
        var result = mvc.perform(post("/api/courses").session(session).with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("title", "  数据结构  ", "description", "  课程内容  ", "ownerId", bob.getId()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.title").value("数据结构"))
                .andExpect(jsonPath("$.data.description").value("课程内容")).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        var alice = users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, "testalice"));
        assertThat(courses.selectById(id).getOwnerId()).isEqualTo(alice.getId());
    }

    @Test
    void usersCannotListOrReadOtherUsersCourses() throws Exception {
        var alice = login("testalice");
        var bob = login("testbob");
        long aliceCourse = create(alice, "Alice 的课程");
        create(bob, "Bob 的课程");
        mvc.perform(get("/api/courses").session(alice)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Alice 的课程"));
        mvc.perform(get("/api/courses/" + aliceCourse).session(bob)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
        mvc.perform(get("/api/courses/99999999").session(bob)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
    }

    @Test
    void invalidCourseFieldsAreRejected() throws Exception {
        var session = login("testalice");
        for (String title : new String[]{"   ", "x".repeat(121)}) {
            mvc.perform(post("/api/courses").session(session).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("title", title))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.title").isNotEmpty());
        }
        mvc.perform(post("/api/courses").session(session).with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("title", "正常标题", "description", "x".repeat(1001)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.description").isNotEmpty());
        assertThat(courses.selectCount(Wrappers.emptyWrapper())).isZero();
    }

    @Test
    void paginationKeywordAndBoundsWork() throws Exception {
        var session = login("testalice");
        for (int i = 1; i <= 5; i++) create(session, "数学 " + i);
        mvc.perform(get("/api/courses").session(session).param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(get("/api/courses").session(session).param("keyword", "数学 3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/courses").session(session).param("keyword", "' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/courses").session(session).param("size", "51")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/courses").session(session).param("page", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void databaseStoresOnlyBcryptHash() {
        var user = users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, "testalice"));
        assertThat(user.getPasswordHash()).startsWith("$2").isNotEqualTo("TestPassword@123");
        assertThat(passwords.matches("TestPassword@123", user.getPasswordHash())).isTrue();
    }
}
