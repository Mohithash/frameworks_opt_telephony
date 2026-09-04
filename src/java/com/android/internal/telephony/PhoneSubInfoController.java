/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import static android.Manifest.permission.MODIFY_PHONE_STATE;
import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;
import static android.Manifest.permission.USE_ICC_AUTH;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.TelephonyServiceManager.ServiceRegisterer;
import android.privacykit.IPrivacyKitManager;
import android.privacykit.PrivacyKitIdentifierGenerator;
import android.privacykit.PrivacyKitKeys;
import android.telephony.ImsiEncryptionInfo;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.flags.FeatureFlagsImpl;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.subscription.SubscriptionInfoInternal;
import com.android.internal.telephony.subscription.SubscriptionManagerService;
import com.android.internal.telephony.uicc.IsimRecords;
import com.android.internal.telephony.uicc.SIMRecords;
import com.android.internal.telephony.uicc.UiccCardApplication;
import com.android.internal.telephony.uicc.UiccPort;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class PhoneSubInfoController extends IPhoneSubInfo.Stub {
    private static final String TAG = "PhoneSubInfoController";
    private static final boolean DBG = true;
    private static final boolean VDBG = false; // STOPSHIP if true

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final Context mContext;
    private AppOpsManager mAppOps;
    private FeatureFlags mFeatureFlags;
    private PackageManager mPackageManager;
    private final int mVendorApiLevel;
    private static PhoneSubInfoController sInstance;

    /**
     * Initialize the PhoneSubInfoController singleton instance and register to
     * TelephonyServiceManager
     */
    public static PhoneSubInfoController init(Context context) {
        synchronized (PhoneSubInfoController.class) {
            if (sInstance == null) {
                sInstance = new PhoneSubInfoController(context);
                ServiceRegisterer phoneSubServiceRegisterer = TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getPhoneSubServiceRegisterer();
                if (phoneSubServiceRegisterer.get() == null) {
                    phoneSubServiceRegisterer.register(sInstance);
                }
            } else {
                Log.wtf(TAG, "PhoneSubInfoController is already initialized.");
            }
        }
        return sInstance;
    }

    public PhoneSubInfoController(Context context) {
        this(context, new FeatureFlagsImpl());
    }

    public PhoneSubInfoController(Context context, FeatureFlags featureFlags) {
        mAppOps = context.getSystemService(AppOpsManager.class);
        mContext = context;
        mPackageManager = context.getPackageManager();
        mFeatureFlags = featureFlags;
        mVendorApiLevel = SystemProperties.getInt(
                "ro.vendor.api_level", Build.VERSION.DEVICE_INITIAL_SDK_INT);
    }

    /**
     * PrivacyKit-Native resolver bridge: ask the {@code privacykit} service to resolve
     * {@code realValue} for {@code callingPackage}, returning {@code realValue} unchanged on any
     * missing package, value, service or error.
     *
     * <p>ROUTE: frameworks/opt/telephony runs in the {@code com.android.phone} process, NOT in
     * system_server, so {@code LocalServices.getService(PrivacyKitManagerInternal.class)} - the
     * route {@code DeviceIdentifiersPolicyService} uses - is unreachable here: LocalServices is
     * process-local to system_server and would always answer null. The published binder
     * ({@code IPrivacyKitManager} via {@code ServiceManager.getService("privacykit")}) is the
     * only route, the same one SettingsProvider takes.
     *
     * <p>A telephony hook site must always fail open and never throw.
     */
    private static String resolveTelephony(String callingPackage, String key, String realValue) {
        if (callingPackage == null || realValue == null) {
            return realValue;
        }
        try {
            IPrivacyKitManager pk = IPrivacyKitManager.Stub.asInterface(
                    ServiceManager.getService("privacykit"));
            if (pk == null) {
                return realValue;
            }
            final String resolved = pk.resolveIdentifier(callingPackage, key, realValue);
            if (resolved == null || resolved.equals(realValue)) {
                return realValue; // no rule - nothing to second-guess, and no extra call
            }
            // A substitution really did happen, so it is worth one more question:
            // was it a RULE_PER_LAUNCH one? See pkStablePerLaunchSubstitute for
            // why that rule type cannot be honoured on this path and what is
            // handed back instead.
            if (pkIsPerLaunch(pk, callingPackage, key)) {
                return pkStablePerLaunchSubstitute(callingPackage, key, realValue);
            }
            return resolved;
        } catch (Throwable t) {
            return realValue;
        }
    }

    /**
     * PrivacyKitRuleResolver RULE_PER_LAUNCH, mirrored as a literal: that class
     * lives in services.jar, which is not on this process classpath.
     */
    private static final int PK_RULE_PER_LAUNCH = 2;

    /**
     * Whether (callingPackage, key) is configured RULE_PER_LAUNCH.
     *
     * <p>Answers false on any failure - missing permission, service gone - so
     * that "cannot tell" falls back to the value the service already resolved.
     * Answering true on a failure would silently replace every rule type on this
     * key with a locally minted value.
     */
    private static boolean pkIsPerLaunch(IPrivacyKitManager pk, String callingPackage,
            String key) {
        try {
            return pk.getRuleType(callingPackage, key) == PK_RULE_PER_LAUNCH;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * PrivacyKit-Native: RULE_PER_LAUNCH cannot mean what it says on a telephony
     * key, so this path refuses it rather than pretending it works.
     *
     * <p>Per-launch is implemented by seeding the substitute with a token that
     * is stable for the lifetime of the READING app process and changes when
     * that app is relaunched; PrivacyKitService derives it from
     * Binder.getCallingPid() plus that pid start time in /proc. That is right
     * when the app itself calls resolveIdentifier - the SSAID path and the
     * ActivityThread Build.* injector do exactly that. It is wrong here. These
     * hooks run inside com.android.phone and resolve on behalf of the app, so
     * the pid PrivacyKitService sees is the PHONE process. The token therefore
     * does not change when the app relaunches, and does change when the phone
     * process restarts - an event the user cannot see and that has nothing to do
     * with the app. That is not per-launch by any reading of the words.
     *
     * <p>Deriving the token from the real caller is not reachable from here: the
     * pid would have to be a parameter of IPrivacyKitManager.resolveIdentifier,
     * and this module cannot add one without renumbering that interface.
     *
     * <p>So the substitute is minted locally from exactly the seed
     * PrivacyKitRuleResolver itself uses when it cannot identify the caller
     * (launchToken == 0, which it documents as degrading to "stable per
     * (package, key)"). What the user asked for - a fake identifier - is still
     * delivered; refusing all the way back to the real IMEI is the one outcome
     * nobody who selected Per-launch wants. It is simply stable per app and per
     * key instead of rotating. Every other rule type is untouched.
     */
    private static String pkStablePerLaunchSubstitute(String callingPackage, String key,
            String realValue) {
        if (!PrivacyKitIdentifierGenerator.hasGeneratorFor(key)
                || PrivacyKitIdentifierGenerator.needsRealValue(key)) {
            // No dedicated generator for this key, so the generic fallback is an
            // opaque hex string - not a plausible MEID, operator name or country
            // code. Keep the real value.
            return realValue;
        }
        return PrivacyKitIdentifierGenerator.generate(key,
                pkStableLaunchSeed(callingPackage, key), realValue);
    }

    /**
     * The seed PrivacyKitRuleResolver would use for RULE_PER_LAUNCH with a
     * launch token of 0. Kept identical to its private seed() - same prime, same
     * 31x accumulation, same "package|material" shape - so the value minted here
     * is the value the service itself would mint for a caller it cannot
     * identify.
     */
    private static long pkStableLaunchSeed(String callingPackage, String key) {
        final String combined = callingPackage + "|" + key + ":launch:0";
        long h = 1125899906842597L;
        for (int i = 0; i < combined.length(); i++) {
            h = 31 * h + combined.charAt(i);
        }
        return h;
    }

    /**
     * PrivacyKit-Native: {@link #resolveTelephony} plus the platform system-caller guard.
     *
     * <p>{@code callingUid} MUST be captured with {@link Binder#getCallingUid()} at the binder
     * entry point, BEFORE the {@code callPhoneMethodFor*} helpers clear the calling identity.
     * Read from inside one of their lambdas it would be the phone process itself, and every app
     * would look like a system caller.
     *
     * <p>Callers below {@link android.os.Process#FIRST_APPLICATION_UID} (system_server,
     * com.android.phone, the radio) read these same identifiers to drive carrier config, SIM
     * provisioning and the SIM UI, so they always get the real value.
     */
    private static String resolveTelephonyForApp(int callingUid, String callingPackage,
            String key, String realValue) {
        if (callingUid < android.os.Process.FIRST_APPLICATION_UID) {
            return realValue;
        }
        return resolveTelephony(callingPackage, key, realValue);
    }

    /**
     * True when {@code value} has the shape of a MEID: 14 hexadecimal characters with at least
     * one non-decimal digit. A 14-character all-decimal value is ambiguous, so it is treated as
     * an IMEI - which is what it is on every GSM/LTE/NR device.
     */
    private static boolean isMeidShaped(String value) {
        if (value == null || value.length() != 14) {
            return false;
        }
        boolean allDecimal = true;
        for (int i = 0; i < 14; i++) {
            char c = value.charAt(i);
            boolean decimal = c >= '0' && c <= '9';
            boolean hex = decimal || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
            if (!decimal) {
                allDecimal = false;
            }
        }
        return !allDecimal;
    }

    /**
     * Shape gate for a substituted MEID: 14 hexadecimal characters, or the empty string for the
     * explicit "Empty" rule. Anything else is malformed, so the real value is returned - a bad
     * rule must never hand an app a value its parser chokes on.
     */
    private static String sanitizeMeid(String substitute, String realValue) {
        if (substitute == null) {
            return realValue;
        }
        if (substitute.isEmpty() || substitute.equals(realValue)) {
            return substitute;
        }
        if (substitute.length() != 14) {
            return realValue;
        }
        for (int i = 0; i < 14; i++) {
            char c = substitute.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return realValue;
            }
        }
        return substitute;
    }

    /**
     * Shape gate for a substituted phone number: E.164-ish, i.e. an optional leading "+" followed
     * by 4 to 15 decimal digits. The empty string is the explicit "Empty" rule and is passed
     * through untouched. Anything else is malformed, so the real value is returned - dialers, SMS
     * apps and carrier apps parse this string, so a bad rule must never reach them.
     */
    private static String sanitizePhoneNumber(String substitute, String realValue) {
        if (substitute == null) {
            return realValue;
        }
        if (substitute.isEmpty() || substitute.equals(realValue)) {
            return substitute;
        }
        String digits = substitute.startsWith("+") ? substitute.substring(1) : substitute;
        return isDecimal(digits, 4, 15) ? substitute : realValue;
    }

    /**
     * Shape gate for a substituted IMEI: 14 to 16 decimal digits (15 in
     * practice; 14 is an IMEI without its check digit and 16 an IMEISV). The
     * empty string is the explicit "Empty" rule and is passed through untouched.
     * Anything else is malformed, so the real value is returned - the same
     * contract SubscriptionManagerService applies to the SIM identity fields it
     * substitutes, and for the same reason: apps parse these strings, so a bad
     * rule must never reach them.
     */
    private static String sanitizeImei(String substitute, String realValue) {
        if (substitute == null) {
            return realValue;
        }
        if (substitute.isEmpty() || substitute.equals(realValue)) {
            return substitute;
        }
        return isDecimal(substitute, 14, 16) ? substitute : realValue;
    }

    /**
     * Shape gate for a substituted IMSI: 6 to 15 decimal digits (15 in practice - 3-digit MCC,
     * 2 or 3 digit MNC, then the MSIN; 15 is the ITU E.212 maximum). The empty string is the
     * explicit "Empty" rule and is passed through untouched. Carrier and SIM-aware apps slice
     * this string by index, so a substitute that is not all digits or is over-length must never
     * reach them.
     */
    private static String sanitizeImsi(String substitute, String realValue) {
        if (substitute == null) {
            return realValue;
        }
        if (substitute.isEmpty() || substitute.equals(realValue)) {
            return substitute;
        }
        return isDecimal(substitute, 6, 15) ? substitute : realValue;
    }

    /**
     * Shape gate for a substituted ICCID: 6 to 22 decimal digits (19-20 in
     * practice). Mirrors SubscriptionManagerService sanitizeIccid, which gates
     * the same value on the SubscriptionInfo path - both have to be gated or the
     * malformed one is simply read through the other API.
     */
    private static String sanitizeIccid(String substitute, String realValue) {
        if (substitute == null) {
            return realValue;
        }
        if (substitute.isEmpty() || substitute.equals(realValue)) {
            return substitute;
        }
        return isDecimal(substitute, 6, 22) ? substitute : realValue;
    }

    /** @return {@code true} if {@code value} is minLength..maxLength ASCII digits. */
    private static boolean isDecimal(String value, int minLength, int maxLength) {
        if (value == null || value.length() < minLength || value.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // ASCII 48..57 only. Character.isDigit() would also accept
            // Arabic-Indic digits, which no telephony parser on the device
            // handles.
            if (c < 48 || c > 57) {
                return false;
            }
        }
        return true;
    }

    @Deprecated
    public String getDeviceId(String callingPackage) {
        return getDeviceIdWithFeature(callingPackage, null);
    }

    public String getDeviceIdWithFeature(String callingPackage, String callingFeatureId) {
        return getDeviceIdForPhone(SubscriptionManager.getPhoneId(getDefaultSubscription()),
                callingPackage, callingFeatureId);
    }

    public String getDeviceIdForPhone(int phoneId, String callingPackage,
            String callingFeatureId) {
        enforceCallingPackageUidMatched(callingPackage);
        // Captured here, at the binder entry point: the helper below clears the calling identity
        // before it runs the lambda, so the uid has to be read now.
        final int callingUid = Binder.getCallingUid();
        return callPhoneMethodForPhoneIdWithReadDeviceIdentifiersCheck(phoneId, callingPackage,
                callingFeatureId, "getDeviceId", (phone) -> {
                    final String real = phone.getDeviceId();
                    // Phone#getDeviceId() is the IMEI on a GSM/LTE/NR phone and the MEID on a
                    // CDMA-capable one, so the value shape - 15 decimal digits vs 14 hex
                    // characters - picks the key. Without this split a MEID would silently
                    // borrow the imei rule and be rewritten into an IMEI-shaped value.
                    if (isMeidShaped(real)) {
                        return sanitizeMeid(resolveTelephonyForApp(callingUid, callingPackage,
                                PrivacyKitKeys.KEY_MEID, real), real);
                    }
                    return sanitizeImei(resolveTelephonyForApp(callingUid, callingPackage,
                            PrivacyKitKeys.KEY_IMEI, real), real);
                });
    }

    public String getNaiForSubscriber(int subId, String callingPackage, String callingFeatureId) {
        return callPhoneMethodForSubIdWithReadSubscriberIdentifiersCheck(subId, callingPackage,
                callingFeatureId, "getNai", (phone)-> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                            "getNaiForSubscriber");

                    return phone.getNai();
                });
    }

    public String getImeiForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        // Captured at the binder entry point: the callPhoneMethodFor* helper clears the calling
        // identity before it runs the lambda, so the uid has to be read now.
        final int callingUid = Binder.getCallingUid();
        return callPhoneMethodForSubIdWithReadDeviceIdentifiersCheck(subId, callingPackage,
                callingFeatureId, "getImei", (phone) -> {
                    final String real = phone.getImei();
                    return sanitizeImei(resolveTelephonyForApp(callingUid, callingPackage,
                            PrivacyKitKeys.KEY_IMEI, real), real);
                });
    }

    public ImsiEncryptionInfo getCarrierInfoForImsiEncryption(int subId, int keyType,
                                                              String callingPackage) {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId,
                "getCarrierInfoForImsiEncryption",
                (phone)-> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                            "getCarrierInfoForImsiEncryption");

                    return phone.getCarrierInfoForImsiEncryption(keyType, true);
                });
    }

    public void setCarrierInfoForImsiEncryption(int subId, String callingPackage,
                                                ImsiEncryptionInfo imsiEncryptionInfo) {
        callPhoneMethodForSubIdWithModifyCheck(subId, callingPackage,
                "setCarrierInfoForImsiEncryption",
                (phone)-> {
                    phone.setCarrierInfoForImsiEncryption(imsiEncryptionInfo, true);
                    return null;
                });
    }

    /**
     *  Resets the Carrier Keys in the database. This involves 2 steps:
     *  1. Delete the keys from the database.
     *  2. Send an intent to download new Certificates.
     *  @param subId
     *  @param callingPackage
     */
    public void resetCarrierKeysForImsiEncryption(int subId, String callingPackage) {
        callPhoneMethodForSubIdWithModifyCheck(subId, callingPackage,
                "resetCarrierKeysForImsiEncryption",
                (phone)-> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                            "resetCarrierKeysForImsiEncryption");

                    phone.resetCarrierKeysForImsiEncryption();
                    return null;
                });
    }

    public String getDeviceSvn(String callingPackage, String callingFeatureId) {
        return getDeviceSvnUsingSubId(getDefaultSubscription(), callingPackage, callingFeatureId);
    }

    public String getDeviceSvnUsingSubId(int subId, String callingPackage,
            String callingFeatureId) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, callingFeatureId,
                "getDeviceSvn", (phone)-> phone.getDeviceSvn());
    }

    @Deprecated
    public String getSubscriberId(String callingPackage) {
        return getSubscriberIdWithFeature(callingPackage, null);
    }

    public String getSubscriberIdWithFeature(String callingPackage, String callingFeatureId) {
        return getSubscriberIdForSubscriber(getDefaultSubscription(), callingPackage,
                callingFeatureId);
    }

    /**
     * COHERENCE: {@code imsi}, {@code sim_operator}, {@code sim_operator_name} and
     * {@code sim_country_iso} all describe ONE carrier and are meant to be minted together by a
     * single Region Preset - one carrier record driving the MCC+MNC, the service provider name,
     * the ISO country and the IMSI prefix (the IMSI opens with the same MCC+MNC as the operator
     * numeric). Substituting only the IMSI yields a SIM whose subscriber id contradicts its own
     * operator numeric, which is a stronger fingerprint than the real value. The preset is the UI
     * contract; this hook deliberately substitutes only what it was asked for.
     */
    public String getSubscriberIdForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        String message = "getSubscriberIdForSubscriber";
        // Captured before any Binder.clearCallingIdentity() below, so the PrivacyKit
        // system-caller guard sees the app uid and not the phone process.
        final int callingUid = Binder.getCallingUid();
        mAppOps.checkPackage(callingUid, callingPackage);

        long identity = Binder.clearCallingIdentity();
        boolean isActive;
        try {
            isActive = SubscriptionManagerService.getInstance().isActiveSubId(subId,
                    callingPackage, callingFeatureId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (isActive) {
            return callPhoneMethodForSubIdWithReadSubscriberIdentifiersCheck(subId, callingPackage,
                    callingFeatureId, message, (phone) -> {
                        enforceTelephonyFeatureWithException(callingPackage,
                                PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                                "getSubscriberIdForSubscriber");

                        final String real = phone.getSubscriberId();
                        return sanitizeImsi(resolveTelephonyForApp(callingUid,
                                callingPackage, PrivacyKitKeys.KEY_IMSI, real), real);
                    });
        } else {
            if (!TelephonyPermissions.checkCallingOrSelfReadSubscriberIdentifiers(
                    mContext, subId, callingPackage, callingFeatureId, message)) {
                return null;
            }

            enforceTelephonyFeatureWithException(callingPackage,
                    PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getSubscriberIdForSubscriber");

            identity = Binder.clearCallingIdentity();
            try {
                SubscriptionInfoInternal subInfo = SubscriptionManagerService.getInstance()
                        .getSubscriptionInfoInternal(subId);
                if (subInfo != null && !TextUtils.isEmpty(subInfo.getImsi())) {
                    final String real = subInfo.getImsi();
                    return sanitizeImsi(resolveTelephonyForApp(callingUid, callingPackage,
                            PrivacyKitKeys.KEY_IMSI, real), real);
                }
                return null;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Deprecated
    public String getIccSerialNumber(String callingPackage) {
        return getIccSerialNumberWithFeature(callingPackage, null);
    }

    /**
     * Retrieves the serial number of the ICC, if applicable.
     */
    public String getIccSerialNumberWithFeature(String callingPackage, String callingFeatureId) {
        return getIccSerialNumberForSubscriber(getDefaultSubscription(), callingPackage,
                callingFeatureId);
    }

    public String getIccSerialNumberForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        // Captured at the binder entry point, before the helper clears the calling identity.
        final int callingUid = Binder.getCallingUid();
        return callPhoneMethodForSubIdWithReadSubscriberIdentifiersCheck(subId, callingPackage,
                callingFeatureId, "getIccSerialNumber", (phone) -> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                            "getIccSerialNumberForSubscriber");

                    final String real = phone.getIccSerialNumber();
                    return sanitizeIccid(resolveTelephonyForApp(callingUid, callingPackage,
                            PrivacyKitKeys.KEY_ICCID, real), real);
                });
    }

    public String getLine1Number(String callingPackage, String callingFeatureId) {
        return getLine1NumberForSubscriber(getDefaultSubscription(), callingPackage,
                callingFeatureId);
    }

    // In R and beyond, READ_PHONE_NUMBERS includes READ_PHONE_NUMBERS and READ_SMS only.
    // Prior to R, it also included READ_PHONE_STATE.  Maintain that for compatibility.
    public String getLine1NumberForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        // PrivacyKit-Native: this is the LEGACY phone-number path
        // (TelephonyManager#getLine1Number). Modern callers read the same fact through
        // SubscriptionManager#getPhoneNumber, served by SubscriptionManagerService, which
        // resolves the same phone_number key. Both have to be wired or the substitution is
        // bypassed by one binder call.
        final int callingUid = Binder.getCallingUid();
        return callPhoneMethodForSubIdWithReadPhoneNumberCheck(
                subId, callingPackage, callingFeatureId, "getLine1Number",
                (phone)-> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                            "getLine1NumberForSubscriber");

                    final String real = phone.getLine1Number();
                    return sanitizePhoneNumber(resolveTelephonyForApp(callingUid, callingPackage,
                            PrivacyKitKeys.KEY_PHONE_NUMBER, real), real);
                });
    }

    public String getLine1AlphaTag(String callingPackage, String callingFeatureId) {
        return getLine1AlphaTagForSubscriber(getDefaultSubscription(), callingPackage,
                callingFeatureId);
    }

    public String getLine1AlphaTagForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, callingFeatureId,
                "getLine1AlphaTag", (phone)-> phone.getLine1AlphaTag());
    }

    public String getMsisdn(String callingPackage, String callingFeatureId) {
        return getMsisdnForSubscriber(getDefaultSubscription(), callingPackage, callingFeatureId);
    }

    // In R and beyond this will require READ_PHONE_NUMBERS.
    // Prior to R it needed READ_PHONE_STATE.  Maintain that for compatibility.
    public String getMsisdnForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        // PrivacyKit-Native: the MSISDN is the subscriber number the SIM publishes in EF_MSISDN,
        // i.e. the very value getLine1Number() usually returns. Left unhooked it would hand back
        // the real number one binder call away from the substituted one.
        final int callingUid = Binder.getCallingUid();
        return callPhoneMethodForSubIdWithReadPhoneNumberCheck(
                subId, callingPackage, callingFeatureId, "getMsisdn", (phone)-> {
                    final String real = phone.getMsisdn();
                    return sanitizePhoneNumber(resolveTelephonyForApp(callingUid, callingPackage,
                            PrivacyKitKeys.KEY_PHONE_NUMBER, real), real);
                });
    }

    public String getVoiceMailNumber(String callingPackage, String callingFeatureId) {
        return getVoiceMailNumberForSubscriber(getDefaultSubscription(), callingPackage,
                callingFeatureId);
    }

    public String getVoiceMailNumberForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, callingFeatureId,
                "getVoiceMailNumber", (phone)-> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_CALLING,
                            "getVoiceMailNumberForSubscriber");

                    String number = PhoneNumberUtils.extractNetworkPortion(
                            phone.getVoiceMailNumber());
                    if (VDBG) log("VM: getVoiceMailNUmber: " + number);
                    return number;
                });
    }

    public String getVoiceMailAlphaTag(String callingPackage, String callingFeatureId) {
        return getVoiceMailAlphaTagForSubscriber(getDefaultSubscription(), callingPackage,
                callingFeatureId);
    }

    public String getVoiceMailAlphaTagForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, callingFeatureId,
                "getVoiceMailAlphaTag", (phone)-> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_CALLING,
                            "getVoiceMailAlphaTagForSubscriber");

                    return phone.getVoiceMailAlphaTag();
                });
    }

    /**
     * get Phone object based on subId.
     **/
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Phone getPhone(int subId) {
        int phoneId = SubscriptionManager.getPhoneId(subId);
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            return null;
        }
        return PhoneFactory.getPhone(phoneId);
    }

    private void enforceCallingPackageUidMatched(String callingPackage) {
        try {
            mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
        } catch (SecurityException se) {
            EventLog.writeEvent(0x534e4554, "188677422", Binder.getCallingUid());
            throw se;
        }
    }

    private boolean enforceIccSimChallengeResponsePermission(Context context, int subId,
            String callingPackage, String callingFeatureId, String message) {
        if (TelephonyPermissions.checkCallingOrSelfUseIccAuthWithDeviceIdentifier(context,
                callingPackage, callingFeatureId, message)) {
            logStackTrace("granted by UseIccAuthWithDeviceId");
            return true;
        }
        if (VDBG) log("No USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER permission.");
        try {
            if (Flags.newSimAuthPermission()) {
                // need to disable requiring the new flag until we can propagate it to GMSC or
                // we'll break things
                // TODO(b/475363442)
                try {
                    enforceUseIccAuthPermissionOrCarrierPrivilege(subId, message);
                    logStackTrace("granted by UseIccAuth or Carrier");
                    return true;
                } catch (SecurityException e) {
                    enforcePrivilegedPermissionOrCarrierPrivilege(subId, message);
                    logStackTrace("granted by privPermissionOrCarrier");
                }
            } else {
                enforcePrivilegedPermissionOrCarrierPrivilege(subId, message);
                logStackTrace("granted by unflagged");
            }
            return true;
        } catch (SecurityException e) {
            logStackTrace("not granted");
            throw(e);
        }
    }

    private void logStackTrace(String msg) {
        Log.e(TAG, msg + " callingUID:" + Binder.getCallingUid(), new Exception());
    }

    /**
     * Make sure caller has either USE_ICC_AUTH or carrier privilege.
     *
     * @throws SecurityException if the caller does not have the required permission/privilege
     */
    private void enforceUseIccAuthPermissionOrCarrierPrivilege(int subId, String message) {
        int permissionResult = mContext.checkCallingOrSelfPermission(
                USE_ICC_AUTH);
        if (permissionResult == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (VDBG) log("No USE_ICC_AUTH permission, check carrier privilege next.");
        TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(mContext, subId, message);
    }

    /**
     * Make sure caller has either read privileged phone permission or carrier privilege.
     *
     * @throws SecurityException if the caller does not have the required permission/privilege
     */
    private void enforcePrivilegedPermissionOrCarrierPrivilege(int subId, String message) {
        // TODO(b/73660190): Migrate to
        // TelephonyPermissions.enforceCallingOrSelfModifyPermissionOrCarrierPrivileges and delete
        // this helper method.
        int permissionResult = mContext.checkCallingOrSelfPermission(
                READ_PRIVILEGED_PHONE_STATE);
        if (permissionResult == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (VDBG) log("No read privileged phone permission, check carrier privilege next.");
        TelephonyPermissions.enforceCallingOrSelfCarrierPrivilege(mContext, subId, message);
    }

    /**
     * Make sure caller has modify phone state permission.
     */
    private void enforceModifyPermission() {
        mContext.enforceCallingOrSelfPermission(MODIFY_PHONE_STATE,
                "Requires MODIFY_PHONE_STATE");
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int getDefaultSubscription() {
        return  PhoneFactory.getDefaultSubscription();
    }

    /**
    * get the Isim Impi based on subId
    */
    public String getIsimImpi(int subId) {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimImpi",
                (phone) -> {
                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimImpi();
                    } else {
                        return null;
                    }
                });
    }

    /**
     * Fetches the IMS private user identity (EF_IMPI) based on subscriptionId.
     *
     * @param subId subscriptionId
     * @return IMPI (IMS private user identity) of type string.
     * @throws IllegalArgumentException if the subscriptionId is not valid
     * @throws IllegalStateException in case the ISIM hasn’t been loaded.
     * @throws SecurityException if the caller does not have the required permission
     */
    public String getImsPrivateUserIdentity(int subId, String callingPackage,
            String callingFeatureId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid SubscriptionID  = " + subId);
        }
        if (!TelephonyPermissions.checkCallingOrSelfUseIccAuthWithDeviceIdentifier(mContext,
                callingPackage, callingFeatureId, "getImsPrivateUserIdentity")) {
            throw (new SecurityException("No permissions to the caller"));
        }
        Phone phone = getPhone(subId);
        assert phone != null;
        IsimRecords isim = phone.getIsimRecords();
        if (isim != null) {
            return isim.getIsimImpi();
        } else {
            throw new IllegalStateException("ISIM is not loaded");
        }
    }

    /**
    * get the Isim Domain based on subId
    */
    public String getIsimDomain(int subId) {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimDomain",
                (phone) -> {
                    enforceTelephonyFeatureWithException(getCurrentPackageName(),
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getIsimDomain");

                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimDomain();
                    } else {
                        return null;
                    }
                });
    }

    /**
    * get the Isim Impu based on subId
    */
    public String[] getIsimImpu(int subId) {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimImpu",
                (phone) -> {
                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimImpu();
                    } else {
                        return null;
                    }
                });
    }

    /**
     * Fetches the ISIM public user identities (EF_IMPU) from UICC based on subId
     *
     * @param subId subscriptionId
     * @param callingPackage package name of the caller
     * @return List of public user identities of type android.net.Uri or empty list  if
     * EF_IMPU is not available.
     * @throws IllegalArgumentException if the subscriptionId is not valid
     * @throws IllegalStateException in case the ISIM hasn’t been loaded.
     * @throws SecurityException if the caller does not have the required permission
     */
    public List<Uri> getImsPublicUserIdentities(int subId, String callingPackage) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription: " + subId);
        }

        TelephonyPermissions
                .enforceCallingOrSelfReadPrivilegedPhoneStatePermissionOrCarrierPrivilege(
                        mContext, subId, "getImsPublicUserIdentities");
        enforceTelephonyFeatureWithException(callingPackage,
                PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getImsPublicUserIdentities");

        Phone phone = getPhone(subId);
        assert phone != null;
        IsimRecords isimRecords = phone.getIsimRecords();
        if (isimRecords != null) {
            String[] impus = isimRecords.getIsimImpu();
            if (impus != null) {
                List<Uri> impuList = new ArrayList<>();
                for (String impu : impus) {
                    if (impu != null && impu.trim().length() > 0) {
                        impuList.add(Uri.parse(impu));
                    }
                }
                return impuList;
            }
        }
        throw new IllegalStateException("ISIM is not loaded");
    }

    /**
    * get the Isim Ist based on subId
    */
    public String getIsimIst(int subId) throws RemoteException {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimIst",
                (phone) -> {
                    enforceTelephonyFeatureWithException(getCurrentPackageName(),
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getIsimIst");

                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimIst();
                    } else {
                        return null;
                    }
                });
    }

    /**
    * get the Isim Pcscf based on subId
    */
    public String[] getIsimPcscf(int subId) throws RemoteException {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getIsimPcscf",
                (phone) -> {
                    IsimRecords isim = phone.getIsimRecords();
                    if (isim != null) {
                        return isim.getIsimPcscf();
                    } else {
                        return null;
                    }
                });
    }

    /**
     * Fetches the IMS Proxy Call Session Control Function(P-CSCF) based on the subscription.
     *
     * @param subId subscriptionId
     * @param callingPackage package name of the caller
     * @return List of IMS Proxy Call Session Control Function strings.
     * @throws IllegalArgumentException if the subscriptionId is not valid
     * @throws IllegalStateException in case the ISIM hasn’t been loaded.
     * @throws SecurityException if the caller does not have the required permission
     */
    public List<String> getImsPcscfAddresses(int subId, String callingPackage) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription: " + subId);
        }

        TelephonyPermissions
                .enforceCallingOrSelfReadPrivilegedPhoneStatePermissionOrCarrierPrivilege(
                        mContext, subId, "getImsPcscfAddresses");
        enforceTelephonyFeatureWithException(callingPackage,
                PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getImsPcscfAddresses");

        Phone phone = getPhone(subId);
        assert phone != null;
        IsimRecords isimRecords = phone.getIsimRecords();
        if (isimRecords != null) {
            String[] pcscfs = isimRecords.getIsimPcscf();
            if (pcscfs != null) {
                List<String> pcscfList = Arrays.stream(pcscfs)
                        .filter(u -> u != null)
                        .map(u -> u.trim())
                        .filter(u -> u.length() > 0)
                        .collect(Collectors.toList());
                return pcscfList;
            }
        }
        throw new IllegalStateException("ISIM is not loaded");
    }

    /**
     * Returns the USIM service table that fetched from EFUST elementary field that are loaded
     * based on the appType.
     */
    public String getSimServiceTable(int subId, int appType) throws RemoteException {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getSimServiceTable",
                (phone) -> {
                    enforceTelephonyFeatureWithException(getCurrentPackageName(),
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getSimServiceTable");

                    UiccPort uiccPort = phone.getUiccPort();
                    if (uiccPort == null || uiccPort.getUiccProfile() == null) {
                        loge("getSimServiceTable(): uiccPort or uiccProfile is null");
                        return null;
                    }
                    UiccCardApplication uiccApp = uiccPort.getUiccProfile().getApplicationByType(
                            appType);
                    if (uiccApp == null) {
                        loge("getSimServiceTable(): no app with specified apptype="
                                + appType);
                        return null;
                    }
                    return ((SIMRecords)uiccApp.getIccRecords()).getSimServiceTable();
                });
    }

    @Override
    public String getIccSimChallengeResponse(int subId, int appType, int authType, String data,
            String callingPackage, String callingFeatureId) throws RemoteException {
        CallPhoneMethodHelper<String> toExecute = (phone)-> {
            enforceTelephonyFeatureWithException(callingPackage,
                    PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getIccSimChallengeResponse");

            UiccPort uiccPort = phone.getUiccPort();
            if (uiccPort == null) {
                loge("getIccSimChallengeResponse() uiccPort is null");
                return null;
            }

            UiccCardApplication uiccApp = uiccPort.getApplicationByType(appType);
            if (uiccApp == null) {
                loge("getIccSimChallengeResponse() no app with specified type -- " + appType);
                return null;
            } else {
                loge("getIccSimChallengeResponse() found app " + uiccApp.getAid()
                        + " specified type -- " + appType);
            }

            if (authType != UiccCardApplication.AUTH_CONTEXT_EAP_SIM
                    && authType != UiccCardApplication.AUTH_CONTEXT_EAP_AKA
                    && authType != UiccCardApplication.AUTH_CONTEXT_GBA_BOOTSTRAP
                    && authType != UiccCardApplication.AUTHTYPE_GBA_NAF_KEY_EXTERNAL) {
                loge("getIccSimChallengeResponse() unsupported authType: " + authType);
                return null;
            }
            return uiccApp.getIccRecords().getIccSimChallengeResponse(authType, data);
        };

        return callPhoneMethodWithPermissionCheck(subId, callingPackage, callingFeatureId,
                "getIccSimChallengeResponse", toExecute,
                this::enforceIccSimChallengeResponsePermission);
    }

    public String getGroupIdLevel1ForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        return callPhoneMethodForSubIdWithReadCheck(subId, callingPackage, callingFeatureId,
                "getGroupIdLevel1", (phone)-> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                            "getGroupIdLevel1ForSubscriber");

                    return phone.getGroupIdLevel1();
                });
    }

    /**
     * Return GroupIdLevel2 for the subscriber
     */
    public String getGroupIdLevel2ForSubscriber(int subId, String callingPackage,
            String callingFeatureId) {
        return callPhoneMethodForSubIdWithPrivilegedCheck(subId,
                "getGroupIdLevel2", (phone)-> {
                    enforceTelephonyFeatureWithException(callingPackage,
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION,
                            "getGroupIdLevel2ForSubscriber");
                    return phone.getGroupIdLevel2();
                });
    }

    /**
     * Fetches the IMS Application Reference Identifier(IARI) based on the subscription.
     *
     * @param subId subscriptionId
     * @param appType the uicc app type
     * @param callingPackage package name of the caller
     * @param callback result receiver to get the iist of IARI strings. The result code is ignored.
     */
    public void getUiccIari(int subId, int appType, String callingPackage,
            ResultReceiver callback) {
        if (!mFeatureFlags.supportImsRegistrationEventDownload()) {
            callbackUiccIari(callback, new ArrayList<>(), null);
            return;
        }
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            callbackUiccIari(callback, null,
                    new IllegalArgumentException("Invalid subscription: " + subId));
            return;
        }
        try {
            TelephonyPermissions
                    .enforceCallingOrSelfReadPrivilegedPhoneStatePermissionOrCarrierPrivilege(
                            mContext, subId, "getUiccIari");
            enforceTelephonyFeatureWithException(callingPackage,
                    PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getUiccIari");
        } catch (SecurityException | UnsupportedOperationException ex) {
            callbackUiccIari(callback, null, ex);
            return;
        }

        Phone phone = getPhone(subId);
        if (phone == null) {
            loge("getUiccIari(): phone is null");
            callbackUiccIari(callback, Collections.emptyList(), null);
            return;
        }
        UiccPort uiccPort = phone.getUiccPort();
        if (uiccPort == null || uiccPort.getUiccProfile() == null) {
            loge("getUiccIari(): uiccPort or uiccProfile is null");
            callbackUiccIari(callback, Collections.emptyList(), null);
            return;
        }
        UiccCardApplication uiccApp = uiccPort.getUiccProfile().getApplicationByType(appType);
        if (uiccApp == null) {
            loge("getUiccIari(): no app with specified apptype=" + appType);
            callbackUiccIari(callback, Collections.emptyList(), null);
            return;
        }

        String[] iaris = uiccApp.getIccRecords().getUiccIari();
        if (iaris == null) {
            loge("getUiccIari(): IARI list is null");
            callbackUiccIari(callback, Collections.emptyList(), null);
            return;
        }
        List<String> iariList = Arrays.stream(iaris)
                .filter(u -> u != null)
                .map(u -> u.trim())
                .filter(u -> u.length() > 0)
                .collect(Collectors.toList());
        callbackUiccIari(callback, iariList, null);
    }

    private void callbackUiccIari(ResultReceiver callback, List<String> iari, Exception ex) {
        Bundle result = new Bundle();
        if (ex != null) {
            result.putParcelable(TelephonyManager.KEY_UICC_IARI_EXCEPTION,
                    new ParcelableException(ex));
        } else {
            result.putStringArrayList(TelephonyManager.KEY_UICC_IARI_LIST,
                    (iari == null)
                            ? new ArrayList<>(Collections.emptyList()) : new ArrayList<>(iari));
        }
        callback.send(0, result);
    }

    /** Below are utility methods that abstracts the flow that many public methods use:
     *  1. Check permission: pass, throw exception, or fails (returns false).
     *  2. clearCallingIdentity.
     *  3. Call a specified phone method and get return value.
     *  4. restoreCallingIdentity and return.
     */
    private interface CallPhoneMethodHelper<T> {
        T callMethod(Phone phone);
    }

    private interface PermissionCheckHelper {
        // Implemented to do whatever permission check it wants.
        // If passes, it should return true.
        // If permission is not granted, throws SecurityException.
        // If permission is revoked by AppOps, return false.
        boolean checkPermission(Context context, int subId, String callingPackage,
                @Nullable String callingFeatureId, String message);
    }

    // Base utility method that others use.
    private <T> T callPhoneMethodWithPermissionCheck(int subId, String callingPackage,
            @Nullable String callingFeatureId, String message,
            CallPhoneMethodHelper<T> callMethodHelper,
            PermissionCheckHelper permissionCheckHelper) {
        if (!permissionCheckHelper.checkPermission(mContext, subId, callingPackage,
                callingFeatureId, message)) {
            return null;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            Phone phone = getPhone(subId);
            if (phone != null) {
                return callMethodHelper.callMethod(phone);
            } else {
                if (VDBG) loge(message + " phone is null for Subscription:" + subId);
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private <T> T callPhoneMethodForSubIdWithReadCheck(int subId, String callingPackage,
            @Nullable String callingFeatureId, String message,
            CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, callingPackage, callingFeatureId,
                message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage)->
                        TelephonyPermissions.checkCallingOrSelfReadPhoneState(
                                aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage));
    }

    private <T> T callPhoneMethodForSubIdWithReadDeviceIdentifiersCheck(int subId,
            String callingPackage, @Nullable String callingFeatureId, String message,
            CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, callingPackage, callingFeatureId,
                message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage)->
                        TelephonyPermissions.checkCallingOrSelfReadDeviceIdentifiers(
                                aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage));
    }

    private <T> T callPhoneMethodForSubIdWithReadSubscriberIdentifiersCheck(int subId,
            String callingPackage, @Nullable String callingFeatureId, String message,
            CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, callingPackage, callingFeatureId,
                message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage)->
                        TelephonyPermissions.checkCallingOrSelfReadSubscriberIdentifiers(
                                aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage));
    }

    private <T> T callPhoneMethodForSubIdWithPrivilegedCheck(
            int subId, String message, CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, null, null, message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage) -> {
                    mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE, message);
                    return true;
                });
    }

    private <T> T callPhoneMethodForSubIdWithModifyCheck(int subId, String callingPackage,
            String message, CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, null, null, message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage)-> {
                    enforceModifyPermission();
                    return true;
                });
    }

    private <T> T callPhoneMethodForSubIdWithReadPhoneNumberCheck(int subId, String callingPackage,
            @NonNull String callingFeatureId, String message,
            CallPhoneMethodHelper<T> callMethodHelper) {
        return callPhoneMethodWithPermissionCheck(subId, callingPackage, callingFeatureId,
                message, callMethodHelper,
                (aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage) ->
                        TelephonyPermissions.checkCallingOrSelfReadPhoneNumber(
                                aContext, aSubId, aCallingPackage, aCallingFeatureId, aMessage));
    }

    private <T> T callPhoneMethodForPhoneIdWithReadDeviceIdentifiersCheck(int phoneId,
            String callingPackage, @Nullable String callingFeatureId, String message,
            CallPhoneMethodHelper<T> callMethodHelper) {
        // Getting subId before doing permission check.
        if (!SubscriptionManager.isValidPhoneId(phoneId)) {
            phoneId = 0;
        }
        final Phone phone = PhoneFactory.getPhone(phoneId);
        if (phone == null) {
            return null;
        }
        if (!TelephonyPermissions.checkCallingOrSelfReadDeviceIdentifiers(mContext,
                phone.getSubId(), callingPackage, callingFeatureId, message)) {
            return null;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            return callMethodHelper.callMethod(phone);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns SIP URI or tel URI of the Public Service Identity of the SM-SC fetched from
     * EF_PSISMSC elementary field as defined in Section 4.5.9 (3GPP TS 31.102).
     * @throws IllegalStateException in case if phone or UiccApplication is not available.
     */
    public Uri getSmscIdentity(int subId, int appType) throws RemoteException {
        Uri smscIdentityUri = callPhoneMethodForSubIdWithPrivilegedCheck(subId, "getSmscIdentity",
                (phone) -> {
                    enforceTelephonyFeatureWithException(getCurrentPackageName(),
                            PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, "getSmscIdentity");

                    try {
                        String smscIdentity = null;
                        UiccPort uiccPort = phone.getUiccPort();
                        UiccCardApplication uiccApp =
                                uiccPort.getUiccProfile().getApplicationByType(
                                        appType);
                        smscIdentity = (uiccApp != null) ? uiccApp.getIccRecords().getSmscIdentity()
                                : null;
                        if (TextUtils.isEmpty(smscIdentity)) {
                            return Uri.EMPTY;
                        }
                        return Uri.parse(smscIdentity);
                    } catch (NullPointerException ex) {
                        Rlog.e(TAG, "getSmscIdentity(): Exception = " + ex);
                        return null;
                    }
                });
        if (smscIdentityUri == null) {
            throw new IllegalStateException("Telephony service error");
        }
        return smscIdentityUri;
    }

    /**
     * Get the current calling package name.
     * @return the current calling package name
     */
    @Nullable
    private String getCurrentPackageName() {
        PackageManager pm = mContext.createContextAsUser(Binder.getCallingUserHandle(), 0)
                .getPackageManager();
        if (pm == null) return null;
        String[] callingPackageNames = pm.getPackagesForUid(Binder.getCallingUid());
        return (callingPackageNames == null) ? null : callingPackageNames[0];
    }

    /**
     * Make sure the device has required telephony feature
     *
     * @throws UnsupportedOperationException if the device does not have required telephony feature
     */
    private void enforceTelephonyFeatureWithException(@Nullable String callingPackage,
            @NonNull String telephonyFeature, @NonNull String methodName) {
        TelephonyUtils.enforceTelephonyFeatureWithException(callingPackage, mPackageManager,
                mVendorApiLevel, telephonyFeature, methodName);
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
