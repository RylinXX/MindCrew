package com.simon.MindCrew;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan({"com.simon.MindCrew.mapper", "com.simon.MindCrew.crew.mapper"})
@EnableAsync
public class MindCrewApplication {

    public static void main(String[] args) {
        SpringApplication.run(MindCrewApplication.class, args);
    }
}
