package com.assistance.device.api;

import com.assistance.device.api.KioskoView;
import com.assistance.device.api.NfcCardView;

import java.util.Optional;
import java.util.UUID;

public interface DeviceApi {

    Optional<NfcCardView> findCardByUid(String uid);

    Optional<UUID> resolveActiveEmployeeIdByCardUid(String uid);

    Optional<KioskoView> findKiosko(UUID kioskoId);
}
