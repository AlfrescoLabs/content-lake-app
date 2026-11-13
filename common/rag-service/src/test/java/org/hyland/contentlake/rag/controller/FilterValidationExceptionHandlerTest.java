package org.hyland.contentlake.rag.controller;

import org.hyland.contentlake.rag.service.FacetsService;
import org.hyland.contentlake.security.UnsafeHxqlFilterException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A rejected caller filter is the caller's error: the advice must turn it into a 400 with the reason,
 * not a 500.
 */
class FilterValidationExceptionHandlerTest {

    @Test
    void anUnsafeFilterFromTheServiceBecomesABadRequest() throws Exception {
        FacetsService service = mock(FacetsService.class);
        when(service.facets(any())).thenThrow(new UnsafeHxqlFilterException("filter closes a parenthesis it did not open"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FacetsController(service))
                .setControllerAdvice(new FilterValidationExceptionHandler())
                .build();

        mvc.perform(post("/api/rag/search/facets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"property\":\"p\",\"topN\":0,\"filter\":\"cin_id = 'none') OR (b = 'x'\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid filter: filter closes a parenthesis it did not open"));
    }
}
