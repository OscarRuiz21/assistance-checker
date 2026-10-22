package com.assistance.device.internal.service.mapper;

import com.assistance.device.internal.domain.Kiosko;
import com.assistance.device.internal.domain.NfcCard;
import com.assistance.device.api.CardStatus;
import com.assistance.device.api.KioskoView;
import com.assistance.device.api.NfcCardView;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class DeviceMapper {

    public NfcCardView toView(NfcCard c) {
        return new NfcCardView(
                c.getId(),
                c.getTenantId(),
                c.getUid(),
                c.getEmployeeId(),
                CardStatus.fromDb(c.getStatus()),
                c.getBlockedReason());
    }

    public KioskoView toView(Kiosko k) {
        return new KioskoView(
                k.getId(),
                k.getTenantId(),
                k.getPlantId(),
                k.getName(),
                k.getType(),
                k.getEnabledMethods() == null ? List.of() : Arrays.asList(k.getEnabledMethods()),
                k.getStatus(),
                k.getLastHeartbeatAt());
    }
}
