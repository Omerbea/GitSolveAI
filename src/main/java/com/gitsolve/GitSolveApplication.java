package com.gitsolve;

import com.gitsolve.config.GitSolveProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(GitSolveProperties.class)
public class GitSolveApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitSolveApplication.class, args);
    }
}
