package com.hospital.escort.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI 3.x 文档元数据。文档入口 /swagger-ui.html, JSON /v3/api-docs */
@Configuration
class OpenApiConfig {

    @Bean
    fun escortOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("医院陪护证服务 escort-pass-service API")
            .version("1.1.0")
            .description(
                "住院患者陪护全生命周期: 申请、审批发证、进出扫码核验、陪护人替换(含夜间发烧临时替换)、" +
                    "无人陪护巡查与看护提醒、违规处理、退证归档。"
            )
    )
}
