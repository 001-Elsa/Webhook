package com.example.webhook.platform.incident;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RuleBasedIncidentAdvisor implements IncidentAdvisor {
    @Override
    public Optional<IncidentDiagnosis> diagnose(IncidentContext context) {
        String error = context.evidence().stream()
                .map(IncidentContext.Evidence::value)
                .reduce("", (left, right) -> left + " " + right).toLowerCase(Locale.ROOT);
        Integer status = context.lastStatusCode();
        if (status != null) {
            if (status == 401) return result("RECEIVER_AUTHENTICATION", .96,
                    "接收端返回 401，优先核对签名密钥、时间戳与认证配置。",
                    context, List.of("核对接收端当前 Secret 版本", "检查签名时间戳允许窗口"), false);
            if (status == 403) return result("RECEIVER_PERMISSION", .95,
                    "接收端明确拒绝当前调用权限。", context,
                    List.of("核对接收端授权策略", "确认来源 IP 或客户端权限"), false);
            if (status == 404) return result("RECEIVER_ENDPOINT_CHANGED", .94,
                    "接收端路径不存在或已变更。", context,
                    List.of("向接收方确认最新 Webhook URL", "修正 Endpoint 后先执行 Dry Run"), false);
            if (status == 408) return result("RECEIVER_TIMEOUT", .92,
                    "接收端在请求窗口内未完成处理。", context,
                    List.of("检查接收端耗时", "评估读取超时和异步接收模式"), true);
            if (status == 429) return result("RECEIVER_RATE_LIMIT", .98,
                    "接收端正在限流。", context,
                    List.of("遵循 Retry-After", "降低 Endpoint 并发和发送速率"), false);
            if (status >= 500) return result("RECEIVER_SERVER_ERROR", .93,
                    "接收端持续返回服务端错误。", context,
                    List.of("联系接收方检查服务状态", "等待恢复后通过审批式 ReplayJob 重放"), true);
        }
        if (error.contains("unknownhost") || error.contains("dns")) return result("DNS_FAILURE", .94,
                "域名解析失败。", context, List.of("核对 DNS 记录", "检查集群 DNS 与出口策略"), false);
        if (error.contains("certificate") || error.contains("ssl") || error.contains("tls")) {
            return result("TLS_FAILURE", .94, "TLS 握手或证书校验失败。", context,
                    List.of("检查证书链、SNI 与有效期", "禁止通过关闭证书校验绕过"), false);
        }
        if (error.contains("connection refused")) return result("CONNECTION_REFUSED", .93,
                "目标主机拒绝连接。", context, List.of("确认接收端监听端口和防火墙"), true);
        if (context.outboxBacklog() > 1000) return result("OUTBOX_BACKLOG", .88,
                "Outbox 积压可能主导当前延迟。", context,
                List.of("检查 Publisher confirm、RabbitMQ 与数据库指标", "按 backlog 扩容 Publisher"), false);
        return result("UNDETERMINED", .35, "证据不足，不能做确定性归因。", context,
                List.of("关联 Trace ID 检查完整时间线", "补充接收端日志后重新诊断"), false);
    }

    private Optional<IncidentDiagnosis> result(String category, double confidence, String summary,
                                                IncidentContext context, List<String> actions, boolean replay) {
        List<String> evidenceIds = context.evidence().stream().map(IncidentContext.Evidence::id).limit(5).toList();
        return Optional.of(new IncidentDiagnosis(category, confidence, summary, evidenceIds, actions, replay,
                "rule-based", null, "rules-v1"));
    }
}
