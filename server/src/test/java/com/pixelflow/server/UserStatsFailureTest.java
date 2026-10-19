package com.pixelflow.server;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiErrors;
import com.pixelflow.server.users.UserStatsController;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserStatsFailureTest {
    @Test void failedQueryReturnsSanitized500InsteadOfFakeZeroCounts() throws Exception {
        // Isolate only the database failure; authentication is covered by the API suite.
        JdbcTemplate db=mock(JdbcTemplate.class, invocation -> {
            throw new DataAccessResourceFailureException("private-db-diagnostic");
        });
        var mvc=MockMvcBuilders.standaloneSetup(new UserStatsController(db))
                .setControllerAdvice(new ApiErrors())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType()==Actor.class;
                    }
                    @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory binderFactory) {
                        return new Actor("test-user","CREATOR","test-session");
                    }
                }).build();
        mvc.perform(get("/api/users/me/stats"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("post_count").doesNotExist())
                .andExpect(jsonPath("follower_count").doesNotExist())
                .andExpect(jsonPath("following_count").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-db-diagnostic"))));
        verify(db).queryForMap(anyString(),eq("test-user"),eq("test-user"),eq("test-user"));
    }
}
