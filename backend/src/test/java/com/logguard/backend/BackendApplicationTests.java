package com.logguard.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

    @MockBean
    RestTemplate restTemplate;

    @Test
    void contextLoads() {
    }

}
