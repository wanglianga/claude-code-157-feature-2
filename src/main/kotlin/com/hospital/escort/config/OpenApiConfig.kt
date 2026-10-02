package com.hospital.escort.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI 3.x 文档元信息; Swagger UI 路径 /swagger-ui.html, 原始 JSON /v3/api-docs */
@Configuration
class OpenApiConfig {

    @Bean
    fun escortOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("医院陪护证服务 API")
            .version("1.1.0")
            .description(
                "住院患者陪护全生命周期: 申请->审批->发证同步->进出核验->常规/临时替换(含空窗巡查与看护提醒)->违规->退证归档。"
            )
    )
}
