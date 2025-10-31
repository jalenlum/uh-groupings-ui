package edu.hawaii.its.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.hawaii.its.api.service.HttpRequestService;
import edu.hawaii.its.groupings.configuration.SpringBootWebApplication;
import edu.hawaii.its.groupings.controller.WithMockUhUser;

@ActiveProfiles("localTest")
@SpringBootTest(classes = { SpringBootWebApplication.class })
public class GeneralRestControllerTest {

    @Autowired
    private WebApplicationContext context;

    @MockBean
    private HttpRequestService httpRequestService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private DateTimeFormatter formatter;

    @BeforeEach
    public void setUp() {
        mockMvc = webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        objectMapper = new ObjectMapper();
        formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    }

    @Test
    @WithMockUhUser
    public void testAnnouncementTimingTransitions() throws Exception {
        // Get current system time
        LocalDateTime now = LocalDateTime.now();

        // Create timeline dates based on current time
        LocalDateTime yesterday = now.minusDays(1);
        LocalDateTime beforeEnd = now.plusSeconds(5);
        LocalDateTime afterStart = now.plusSeconds(10);
        LocalDateTime tomorrow = now.plusDays(1);

        // Create the initial test JSON (BEFORE message active, AFTER message future)
        String initialTestJson = createTestAnnouncementJsonWithTime(yesterday, beforeEnd, afterStart, tomorrow, now);

        // Create the later test JSON (BEFORE message expired, AFTER message active)
        LocalDateTime laterNow = now.plusSeconds(10);
        String laterTestJson = createTestAnnouncementJsonWithTime(yesterday, beforeEnd, afterStart, tomorrow, laterNow);

        // Mock the HTTP request service to return different responses
        when(httpRequestService.makeApiRequest(anyString(), eq(HttpMethod.GET)))
                .thenReturn(new ResponseEntity<>(initialTestJson, HttpStatus.OK))
                .thenReturn(new ResponseEntity<>(laterTestJson, HttpStatus.OK));

        // Test 1: Verify BEFORE message is active initially
        MvcResult result1 = mockMvc.perform(get("/announcements").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        String response1 = result1.getResponse().getContentAsString();
        JsonNode jsonResponse1 = objectMapper.readTree(response1);
        JsonNode announcements1 = jsonResponse1.get("announcements");

        assertNotNull(announcements1);
        assertEquals(2, announcements1.size());

        // Find the BEFORE message
        JsonNode beforeMessage = findAnnouncementByMessage(announcements1, "UH Groupings will be updated BEFORE.");
        assertNotNull(beforeMessage);
        assertEquals("Active", beforeMessage.get("state").asText());

        // Find the AFTER message (should be Future initially)
        JsonNode afterMessage1 =
                findAnnouncementByMessage(announcements1, "UH Groupings has been updated as of AFTER.");
        assertNotNull(afterMessage1);
        assertEquals("Future", afterMessage1.get("state").asText());

        // Wait for 11 seconds to trigger the transition
        TimeUnit.SECONDS.sleep(11);

        // Test 2: Verify AFTER message becomes active after transition
        MvcResult result2 = mockMvc.perform(get("/announcements").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        String response2 = result2.getResponse().getContentAsString();
        JsonNode jsonResponse2 = objectMapper.readTree(response2);
        JsonNode announcements2 = jsonResponse2.get("announcements");

        assertNotNull(announcements2);
        assertEquals(2, announcements2.size());

        // Find the AFTER message
        JsonNode afterMessage = findAnnouncementByMessage(announcements2, "UH Groupings has been updated as of AFTER.");
        assertNotNull(afterMessage);
        assertEquals("Active", afterMessage.get("state").asText());

        // Verify BEFORE message is now expired
        JsonNode beforeMessageAfter = findAnnouncementByMessage(announcements2, "UH Groupings will be updated BEFORE.");
        assertNotNull(beforeMessageAfter);
        assertEquals("Expired", beforeMessageAfter.get("state").asText());
    }

    private String createTestAnnouncementJsonWithTime(LocalDateTime beforeStart, LocalDateTime beforeEnd,
            LocalDateTime afterStart, LocalDateTime afterEnd,
            LocalDateTime currentTime) {
        return String.format(
                "{\n" +
                        "  \"resultCode\": \"SUCCESS\",\n" +
                        "  \"announcements\": [\n" +
                        "    {\n" +
                        "      \"message\": \"UH Groupings will be updated BEFORE.\",\n" +
                        "      \"start\": \"%s\",\n" +
                        "      \"end\": \"%s\",\n" +
                        "      \"state\": \"%s\"\n" +
                        "    },\n" +
                        "    {\n" +
                        "      \"message\": \"UH Groupings has been updated as of AFTER.\",\n" +
                        "      \"start\": \"%s\",\n" +
                        "      \"end\": \"%s\",\n" +
                        "      \"state\": \"%s\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}",
                beforeStart.format(formatter),
                beforeEnd.format(formatter),
                getStateForTime(beforeStart, beforeEnd, currentTime),
                afterStart.format(formatter),
                afterEnd.format(formatter),
                getStateForTime(afterStart, afterEnd, currentTime)
        );
    }

    private String getStateForTime(LocalDateTime start, LocalDateTime end, LocalDateTime currentTime) {
        if (currentTime.isBefore(start)) {
            return "Future";
        } else if (currentTime.isAfter(end)) {
            return "Expired";
        } else {
            return "Active";
        }
    }

    private JsonNode findAnnouncementByMessage(JsonNode announcements, String message) {
        for (JsonNode announcement : announcements) {
            if (message.equals(announcement.get("message").asText())) {
                return announcement;
            }
        }
        return null;
    }
}

