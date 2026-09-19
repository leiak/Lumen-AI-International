package com.lumen.extension.approval;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ApprovalRouter {
    private final ApprovalProperties props;

    public List<ApprovalChainConfig> getChain(String bizType) {
        return props.getChains().getOrDefault(bizType, Collections.emptyList());
    }

    public boolean knowsBizType(String bizType) {
        return props.getChains().containsKey(bizType);
    }
}
