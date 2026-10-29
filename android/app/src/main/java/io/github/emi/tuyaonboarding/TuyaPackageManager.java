package io.github.emi.tuyaonboarding;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.pm.VersionedPackage;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

final class TuyaPackageManager extends PackageManager {
    static final String OFFICIAL_PACKAGE_NAME = "com.tuya.smart";
    private static final String LOG_TAG = "TuyaIdentity";
    private static boolean digestLogged;

    private final PackageManager delegate;
    private final String actualPackageName;
    private final Signature officialSignature;
    private final String officialSignatureSha256;

    TuyaPackageManager(PackageManager delegate, Resources resources, String actualPackageName) {
        this.delegate = delegate;
        this.actualPackageName = actualPackageName;
        byte[] signatureDer = readSignature(resources);
        this.officialSignature = new Signature(signatureDer);
        this.officialSignatureSha256 = sha256(signatureDer);
    }

    private static byte[] readSignature(Resources resources) {
        try (InputStream input = resources.openRawResource(R.raw.tuya_signature)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load embedded Tuya signature", e);
        }
    }

    private static String sha256(byte[] der) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static synchronized void logDerSha256Once(String sha256) {
        if (digestLogged) {
            return;
        }
        Log.d(LOG_TAG, "DER SHA-256=" + sha256);
        digestLogged = true;
    }

    private String delegatePackageName(String packageName) {
        return OFFICIAL_PACKAGE_NAME.equals(packageName) ? actualPackageName : packageName;
    }

    private ComponentName delegateComponentName(ComponentName component) {
        return OFFICIAL_PACKAGE_NAME.equals(component.getPackageName())
                ? new ComponentName(actualPackageName, component.getClassName())
                : component;
    }

    private <T extends ComponentInfo> T withOfficialComponentPackage(T componentInfo) {
        componentInfo.packageName = OFFICIAL_PACKAGE_NAME;
        componentInfo.applicationInfo.packageName = OFFICIAL_PACKAGE_NAME;
        componentInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        return componentInfo;
    }

    private PackageInfo withOfficialSignature(PackageInfo packageInfo) {
        packageInfo.packageName = OFFICIAL_PACKAGE_NAME;
        packageInfo.applicationInfo.packageName = OFFICIAL_PACKAGE_NAME;
        packageInfo.applicationInfo.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        packageInfo.signatures = new Signature[]{officialSignature};
        Log.d(LOG_TAG, "packageName=" + packageInfo.packageName
                + " signatures.length=" + packageInfo.signatures.length);
        logDerSha256Once(officialSignatureSha256);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28Impl.setSigningInfo(packageInfo, officialSignature);
        }
        return packageInfo;
    }

    @TargetApi(Build.VERSION_CODES.P)
    private static final class Api28Impl {
        private static void setSigningInfo(PackageInfo packageInfo, Signature signature) {
            Collection<Signature> signers = Collections.singletonList(signature);
            Collection<PublicKey> publicKeys = Collections.emptyList();
            Collection<Signature> pastSignatures = Collections.emptyList();
            SigningInfo signingInfo = new SigningInfo(2, signers, publicKeys, pastSignatures);
            packageInfo.signingInfo = signingInfo;
            Log.d(LOG_TAG, "signingInfo.getApkContentsSigners().length="
                    + signingInfo.getApkContentsSigners().length
                    + " signingInfo.getSigningCertificateHistory().length="
                    + signingInfo.getSigningCertificateHistory().length);
        }
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
        return withOfficialSignature(delegate.getPackageInfo(delegatePackageName(packageName), flags));
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public PackageInfo getPackageInfo(String packageName, PackageInfoFlags flags) throws NameNotFoundException {
        return withOfficialSignature(delegate.getPackageInfo(delegatePackageName(packageName), flags));
    }

    @Override
    public PackageInfo getPackageInfo(VersionedPackage versionedPackage, int flags) throws NameNotFoundException {
        VersionedPackage delegatePackage = OFFICIAL_PACKAGE_NAME.equals(versionedPackage.getPackageName())
                ? new VersionedPackage(actualPackageName, versionedPackage.getVersionCode())
                : versionedPackage;
        return withOfficialSignature(delegate.getPackageInfo(delegatePackage, flags));
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public PackageInfo getPackageInfo(VersionedPackage versionedPackage, PackageInfoFlags flags) throws NameNotFoundException {
        VersionedPackage delegatePackage = OFFICIAL_PACKAGE_NAME.equals(versionedPackage.getPackageName())
                ? new VersionedPackage(actualPackageName, versionedPackage.getVersionCode())
                : versionedPackage;
        return withOfficialSignature(delegate.getPackageInfo(delegatePackage, flags));
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        return delegate.currentToCanonicalPackageNames(names);
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        return delegate.canonicalToCurrentPackageNames(names);
    }

    @Override
    public Intent getLaunchIntentForPackage(String packageName) {
        return delegate.getLaunchIntentForPackage(packageName);
    }

    @Override
    public Intent getLeanbackLaunchIntentForPackage(String packageName) {
        return delegate.getLeanbackLaunchIntentForPackage(packageName);
    }

    @Override
    public int[] getPackageGids(String packageName) throws NameNotFoundException {
        return delegate.getPackageGids(packageName);
    }

    @Override
    public int[] getPackageGids(String packageName, int flags) throws NameNotFoundException {
        return delegate.getPackageGids(packageName, flags);
    }

    @Override
    public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
        return delegate.getPackageUid(packageName, flags);
    }

    @Override
    public PermissionInfo getPermissionInfo(String name, int flags) throws NameNotFoundException {
        return delegate.getPermissionInfo(name, flags);
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) throws NameNotFoundException {
        return delegate.queryPermissionsByGroup(group, flags);
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String name, int flags) throws NameNotFoundException {
        return delegate.getPermissionGroupInfo(name, flags);
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        return delegate.getAllPermissionGroups(flags);
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
        ApplicationInfo applicationInfo = delegate.getApplicationInfo(delegatePackageName(packageName), flags);
        applicationInfo.packageName = OFFICIAL_PACKAGE_NAME;
        applicationInfo.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        return applicationInfo;
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public ApplicationInfo getApplicationInfo(String packageName, ApplicationInfoFlags flags) throws NameNotFoundException {
        ApplicationInfo applicationInfo = delegate.getApplicationInfo(delegatePackageName(packageName), flags);
        applicationInfo.packageName = OFFICIAL_PACKAGE_NAME;
        applicationInfo.flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        return applicationInfo;
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags) throws NameNotFoundException {
        return withOfficialComponentPackage(delegate.getActivityInfo(delegateComponentName(component), flags));
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public ActivityInfo getActivityInfo(ComponentName component, ComponentInfoFlags flags) throws NameNotFoundException {
        return withOfficialComponentPackage(delegate.getActivityInfo(delegateComponentName(component), flags));
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags) throws NameNotFoundException {
        return withOfficialComponentPackage(delegate.getReceiverInfo(delegateComponentName(component), flags));
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, ComponentInfoFlags flags) throws NameNotFoundException {
        return withOfficialComponentPackage(delegate.getReceiverInfo(delegateComponentName(component), flags));
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags) throws NameNotFoundException {
        return withOfficialComponentPackage(delegate.getServiceInfo(delegateComponentName(component), flags));
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public ServiceInfo getServiceInfo(ComponentName component, ComponentInfoFlags flags) throws NameNotFoundException {
        return withOfficialComponentPackage(delegate.getServiceInfo(delegateComponentName(component), flags));
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags) throws NameNotFoundException {
        return withOfficialComponentPackage(delegate.getProviderInfo(delegateComponentName(component), flags));
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    @Override
    public ProviderInfo getProviderInfo(ComponentName component, ComponentInfoFlags flags) throws NameNotFoundException {
        return withOfficialComponentPackage(delegate.getProviderInfo(delegateComponentName(component), flags));
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags) {
        return delegate.getInstalledPackages(flags);
    }

    @Override
    public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags) {
        return delegate.getPackagesHoldingPermissions(permissions, flags);
    }

    @Override
    public int checkPermission(String permissionName, String packageName) {
        return delegate.checkPermission(permissionName, packageName);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String permissionName, String packageName) {
        return delegate.isPermissionRevokedByPolicy(permissionName, packageName);
    }

    @Override
    public boolean addPermission(PermissionInfo info) {
        return delegate.addPermission(info);
    }

    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        return delegate.addPermissionAsync(info);
    }

    @Override
    public void removePermission(String name) {
        delegate.removePermission(name);
    }

    @Override
    public int checkSignatures(String packageName1, String packageName2) {
        return delegate.checkSignatures(packageName1, packageName2);
    }

    @Override
    public int checkSignatures(int uid1, int uid2) {
        return delegate.checkSignatures(uid1, uid2);
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        return delegate.getPackagesForUid(uid);
    }

    @Override
    public String getNameForUid(int uid) {
        return delegate.getNameForUid(uid);
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags) {
        return delegate.getInstalledApplications(flags);
    }

    @Override
    public boolean isInstantApp() {
        return delegate.isInstantApp();
    }

    @Override
    public boolean isInstantApp(String packageName) {
        return delegate.isInstantApp(packageName);
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        return delegate.getInstantAppCookieMaxBytes();
    }

    @Override
    public byte[] getInstantAppCookie() {
        return delegate.getInstantAppCookie();
    }

    @Override
    public void clearInstantAppCookie() {
        delegate.clearInstantAppCookie();
    }

    @Override
    public void updateInstantAppCookie(byte[] cookie) {
        delegate.updateInstantAppCookie(cookie);
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        return delegate.getSystemSharedLibraryNames();
    }

    @Override
    public List<SharedLibraryInfo> getSharedLibraries(int flags) {
        return delegate.getSharedLibraries(flags);
    }

    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber) {
        return delegate.getChangedPackages(sequenceNumber);
    }

    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        return delegate.getSystemAvailableFeatures();
    }

    @Override
    public boolean hasSystemFeature(String name) {
        return delegate.hasSystemFeature(name);
    }

    @Override
    public boolean hasSystemFeature(String name, int version) {
        return delegate.hasSystemFeature(name, version);
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags) {
        return delegate.resolveActivity(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        return delegate.queryIntentActivities(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics, Intent intent, int flags) {
        return delegate.queryIntentActivityOptions(caller, specifics, intent, flags);
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        return delegate.queryBroadcastReceivers(intent, flags);
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags) {
        return delegate.resolveService(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        return delegate.queryIntentServices(intent, flags);
    }

    @Override
    public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
        return delegate.queryIntentContentProviders(intent, flags);
    }

    @Override
    public ProviderInfo resolveContentProvider(String name, int flags) {
        return delegate.resolveContentProvider(name, flags);
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
        return delegate.queryContentProviders(processName, uid, flags);
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(ComponentName className, int flags) throws NameNotFoundException {
        return delegate.getInstrumentationInfo(className, flags);
    }

    @Override
    public List<InstrumentationInfo> queryInstrumentation(String targetPackage, int flags) {
        return delegate.queryInstrumentation(targetPackage, flags);
    }

    @Override
    public Drawable getDrawable(String packageName, int resid, ApplicationInfo applicationInfo) {
        return delegate.getDrawable(packageName, resid, applicationInfo);
    }

    @Override
    public Drawable getActivityIcon(ComponentName activityName) throws NameNotFoundException {
        return delegate.getActivityIcon(activityName);
    }

    @Override
    public Drawable getActivityIcon(Intent intent) throws NameNotFoundException {
        return delegate.getActivityIcon(intent);
    }

    @Override
    public Drawable getActivityBanner(ComponentName activityName) throws NameNotFoundException {
        return delegate.getActivityBanner(activityName);
    }

    @Override
    public Drawable getActivityBanner(Intent intent) throws NameNotFoundException {
        return delegate.getActivityBanner(intent);
    }

    @Override
    public Drawable getDefaultActivityIcon() {
        return delegate.getDefaultActivityIcon();
    }

    @Override
    public Drawable getApplicationIcon(ApplicationInfo info) {
        return delegate.getApplicationIcon(info);
    }

    @Override
    public Drawable getApplicationIcon(String packageName) throws NameNotFoundException {
        return delegate.getApplicationIcon(packageName);
    }

    @Override
    public Drawable getApplicationBanner(ApplicationInfo info) {
        return delegate.getApplicationBanner(info);
    }

    @Override
    public Drawable getApplicationBanner(String packageName) throws NameNotFoundException {
        return delegate.getApplicationBanner(packageName);
    }

    @Override
    public Drawable getActivityLogo(ComponentName activityName) throws NameNotFoundException {
        return delegate.getActivityLogo(activityName);
    }

    @Override
    public Drawable getActivityLogo(Intent intent) throws NameNotFoundException {
        return delegate.getActivityLogo(intent);
    }

    @Override
    public Drawable getApplicationLogo(ApplicationInfo info) {
        return delegate.getApplicationLogo(info);
    }

    @Override
    public Drawable getApplicationLogo(String packageName) throws NameNotFoundException {
        return delegate.getApplicationLogo(packageName);
    }

    @Override
    public Drawable getUserBadgedIcon(Drawable icon, UserHandle user) {
        return delegate.getUserBadgedIcon(icon, user);
    }

    @Override
    public Drawable getUserBadgedDrawableForDensity(Drawable drawable, UserHandle user, Rect badgeLocation, int badgeDensity) {
        return delegate.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity);
    }

    @Override
    public CharSequence getUserBadgedLabel(CharSequence label, UserHandle user) {
        return delegate.getUserBadgedLabel(label, user);
    }

    @Override
    public CharSequence getText(String packageName, int resid, ApplicationInfo applicationInfo) {
        return delegate.getText(packageName, resid, applicationInfo);
    }

    @Override
    public XmlResourceParser getXml(String packageName, int resid, ApplicationInfo applicationInfo) {
        return delegate.getXml(packageName, resid, applicationInfo);
    }

    @Override
    public CharSequence getApplicationLabel(ApplicationInfo info) {
        return delegate.getApplicationLabel(info);
    }

    @Override
    public Resources getResourcesForActivity(ComponentName activityName) throws NameNotFoundException {
        return delegate.getResourcesForActivity(activityName);
    }

    @Override
    public Resources getResourcesForApplication(ApplicationInfo app) throws NameNotFoundException {
        return delegate.getResourcesForApplication(app);
    }

    @Override
    public Resources getResourcesForApplication(String appPackageName) throws NameNotFoundException {
        return delegate.getResourcesForApplication(appPackageName);
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) {
        delegate.verifyPendingInstall(id, verificationCode);
    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout, long millisecondsToDelay) {
        delegate.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay);
    }

    @Override
    public void setInstallerPackageName(String targetPackage, String installerPackageName) {
        delegate.setInstallerPackageName(targetPackage, installerPackageName);
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        return delegate.getInstallerPackageName(packageName);
    }

    @Override
    public void addPackageToPreferred(String packageName) {
        delegate.addPackageToPreferred(packageName);
    }

    @Override
    public void removePackageFromPreferred(String packageName) {
        delegate.removePackageFromPreferred(packageName);
    }

    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        return delegate.getPreferredPackages(flags);
    }

    @Override
    public void addPreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity) {
        delegate.addPreferredActivity(filter, match, set, activity);
    }

    @Override
    public void clearPackagePreferredActivities(String packageName) {
        delegate.clearPackagePreferredActivities(packageName);
    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters, List<ComponentName> outActivities, String packageName) {
        return delegate.getPreferredActivities(outFilters, outActivities, packageName);
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName, int newState, int flags) {
        delegate.setComponentEnabledSetting(componentName, newState, flags);
    }

    @Override
    public int getComponentEnabledSetting(ComponentName componentName) {
        return delegate.getComponentEnabledSetting(componentName);
    }

    @Override
    public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
        delegate.setApplicationEnabledSetting(packageName, newState, flags);
    }

    @Override
    public int getApplicationEnabledSetting(String packageName) {
        return delegate.getApplicationEnabledSetting(packageName);
    }

    @Override
    public boolean isSafeMode() {
        return delegate.isSafeMode();
    }

    @Override
    public void setApplicationCategoryHint(String packageName, int categoryHint) {
        delegate.setApplicationCategoryHint(packageName, categoryHint);
    }

    @Override
    public PackageInstaller getPackageInstaller() {
        return delegate.getPackageInstaller();
    }

    @Override
    public boolean canRequestPackageInstalls() {
        return delegate.canRequestPackageInstalls();
    }
}
