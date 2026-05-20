package com.example.agileagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.agileagent.mapper") // 告诉 MyBatis 去哪个包里找 Mapper 接口
public class AgileAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgileAgentApplication.class, args);
    }

}
