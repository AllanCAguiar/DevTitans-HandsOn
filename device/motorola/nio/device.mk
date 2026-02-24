#
# Copyright (C) 2022 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from sm8250-common
$(call inherit-product, device/motorola/sm8250-common/common.mk)

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Overlays
DEVICE_PACKAGE_OVERLAYS += \
    $(LOCAL_PATH)/overlay-lineage

PRODUCT_PACKAGES += \
    FrameworksResNio \
    SystemUIResNio

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/audio/audio_platform_info.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_platform_info.xml \
    $(LOCAL_PATH)/audio/mixer_paths.xml:$(TARGET_COPY_OUT_VENDOR)/etc/mixer_paths.xml

# Init
PRODUCT_PACKAGES += \
    init.device.rc

# Media
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/media_profiles_V1_0.xml:$(TARGET_COPY_OUT_ODM)/etc/media_profiles_V1_0.xml

# NFC
PRODUCT_PACKAGES += \
    android.hardware.nfc@1.2-service

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/nfc/libnfc-nci.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-nci.conf \
    $(LOCAL_PATH)/nfc/libnfc-nxp.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-nxp.conf


PRODUCT_COPY_FILES += \
    device/motorola/nio/prebuilts/adbd:$(TARGET_COPY_OUT_VENDOR)/bin/adbd_charger \
    device/motorola/nio/prebuilts/linker64:$(TARGET_COPY_OUT_VENDOR)/bin/linker64_charger \
    device/motorola/nio/prebuilts/libs/ld-android.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/ld-android.so \
    device/motorola/nio/prebuilts/libs/libadbd_auth.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libadbd_auth.so \
    device/motorola/nio/prebuilts/libs/libadbd_fs.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libadbd_fs.so \
    device/motorola/nio/prebuilts/libs/libbase.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libbase.so \
    device/motorola/nio/prebuilts/libs/libc++.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libc++.so \
    device/motorola/nio/prebuilts/libs/libc.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libc.so \
    device/motorola/nio/prebuilts/libs/libcrypto.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libcrypto.so \
    device/motorola/nio/prebuilts/libs/libdl.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libdl.so \
    device/motorola/nio/prebuilts/libs/liblog.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/liblog.so \
    device/motorola/nio/prebuilts/libs/libm.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libm.so \
    device/motorola/nio/prebuilts/libs/libpackagelistparser.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libpackagelistparser.so \
    device/motorola/nio/prebuilts/libs/libpcre2.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libpcre2.so \
    device/motorola/nio/prebuilts/libs/libselinux.so:$(TARGET_COPY_OUT_VENDOR)/lib64/adbd/libselinux.so
    
# Get non-open-source specific aspects
$(call inherit-product-if-exists, vendor/motorola/nio/nio-vendor.mk)
