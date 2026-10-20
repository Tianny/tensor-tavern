package com.hify.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Hify 启动入口。
 * 各业务模块的包根为 com.hify.{module}，不在默认扫描路径内，
 * 因此显式指定 scanBasePackages 扫描整个 com.hify。
 * Mapper 统一放在各模块的 mapper 包下，这里按通配符扫描。
 */
@SpringBootApplication(scanBasePackages = "com.hify")
@MapperScan("com.hify.**.mapper")
public class HifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(HifyApplication.class, args);
    }
}
