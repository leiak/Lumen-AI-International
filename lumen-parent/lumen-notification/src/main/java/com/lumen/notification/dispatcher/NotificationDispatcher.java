package com.lumen.notification.dispatcher;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lumen.common.api.R;
import com.lumen.common.error.BizException;
import com.lumen.common.tenant.TenantContext;
import com.lumen.notification.channel.ChannelProvider;
import com.lumen.notification.entity.NotificationSendLog;
import com.lumen.notification.entity.NotificationTemplate;
import com.lumen.notification.error.NotificationErrorCode;
import com.lumen.notification.mapper.NotificationSendLogMapper;
import com.lumen.notification.mapper.NotificationTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final List<ChannelProvider> providers;
    private final NotificationTemplateMapper templateMapper;
    private final NotificationSendLogMapper sendLogMapper;

    private Map<String, ChannelProvider> providerMap;

    private Map<String, ChannelProvider> providerMap() {
        if (providerMap == null) {
            providerMap = providers.stream().collect(Collectors.toMap(ChannelProvider::channel, p -> p));
        }
        return providerMap;
    }

    public R<Void> send(String templateCode, String receiver, Map<String, Object> vars) {
        NotificationTemplate tpl = templateMapper.selectOne(
                new QueryWrapper<NotificationTemplate>().eq("code", templateCode));
        if (tpl == null) throw BizException.of(NotificationErrorCode.TEMPLATE_NOT_FOUND);

        ChannelProvider p = providerMap().get(tpl.getChannel());
        if (p == null) throw BizException.of(NotificationErrorCode.CHANNEL_PROVIDER_MISSING);

        String content = render(tpl.getContent(), vars);
        ChannelProvider.SendResult result = p.send(
                new ChannelProvider.SendRequest(templateCode, receiver, tpl.getSubject(), content, vars));

        NotificationSendLog log = new NotificationSendLog();
        log.setTemplateCode(templateCode);
        log.setChannel(tpl.getChannel());
        log.setReceiver(receiver);
        log.setPayload(content);
        log.setStatus(result.ok() ? 1 : 0);
        log.setErrorMsg(result.error());
        log.setTenantId(TenantContext.get());
        log.setCreatedAt(LocalDateTime.now());
        sendLogMapper.insert(log);

        return R.ok(null);
    }

    private String render(String tpl, Map<String, Object> vars) {
        if (tpl == null) return null;
        if (vars == null || vars.isEmpty()) return tpl;
        String r = tpl;
        for (var e : vars.entrySet()) {
            r = r.replace("${" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return r;
    }
}
