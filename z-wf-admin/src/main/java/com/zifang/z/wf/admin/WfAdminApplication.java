package com.zifang.z.wf.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * z-wf 独立可启动应用。
 *
 * <p>对齐 z-camuda-admin 的定位：<b>永不上 Maven Central</b>（pom 里
 * {@code maven.deploy.skip=true}），只作 Docker 镜像源或本地 {@code java -jar} 演示。
 *
 * <p>零外部依赖启动：
 * <pre>{@code
 * mvn -pl z-wf-admin -am package -DskipTests
 * java -jar z-wf-admin/target/z-wf-admin-1.0.0-exec.jar --spring.profiles.active=h2-test
 * }</pre>
 * h2-test profile 用 H2 内存库 + 关闭 z-config/z-rpc，
 * 起来后 {@code /api/wf/health} 与 {@code /doc.html} 立即可用。
 *
 * @author zifang
 */
@SpringBootApplication
public class WfAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(WfAdminApplication.class, args);
    }
}
