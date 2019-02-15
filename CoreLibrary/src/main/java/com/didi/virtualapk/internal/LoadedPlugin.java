/*
 * Copyright (C) 2017 Beijing Didi Infinity Technology and Development Co.,Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.didi.virtualapk.internal;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.VersionedPackage;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.didi.virtualapk.PluginManager;
import com.didi.virtualapk.internal.utils.DexUtil;
import com.didi.virtualapk.internal.utils.PackageParserCompat;
import com.didi.virtualapk.internal.utils.PluginUtil;
import com.didi.virtualapk.utils.Reflector;
import com.didi.virtualapk.utils.RunUtil;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * Created by renyugang on 16/8/9.
 */
public class LoadedPlugin {

    public static final String TAG = Constants.TAG_PREFIX + "LoadedPlugin";

    protected File getDir(Context context, String name) {
        return context.getDir(name, Context.MODE_PRIVATE);
    }
    
    protected ClassLoader createClassLoader(Context context, File apk, File libsDir, ClassLoader parent) throws Exception {
        File dexOutputDir = getDir(context, Constants.OPTIMIZE_DIR);
        String dexOutputPath = dexOutputDir.getAbsolutePath();
        DexClassLoader loader = new DexClassLoader(apk.getAbsolutePath(), dexOutputPath, libsDir.getAbsolutePath(), parent);

        //宿主和插件有同一个类(包名、类名相同)， 如果COMBINE_CLASSLOADER为true则插件会加载宿主中的类
        if (Constants.COMBINE_CLASSLOADER) {
            //将parent的dex插入到loader里，并插入到前部
            DexUtil.insertDex(loader, parent, libsDir);
        }

        return loader;
    }

    protected AssetManager createAssetManager(Context context, File apk) throws Exception {
        AssetManager am = AssetManager.class.newInstance();
        Reflector.with(am).method("addAssetPath", String.class).call(apk.getAbsolutePath());
        return am;
    }

    protected Resources createResources(Context context, String packageName, File apk) throws Exception {
        if (Constants.COMBINE_RESOURCES) {
            //将所有插件中的资源合并到宿主中，插件就可以访问宿主的资源。
            return ResourcesManager.createResources(context, packageName, apk);
        } else {
            //插件只能访问自己的资源，无法访问宿主以及其它插件的资源。
            Resources hostResources = context.getResources();
            AssetManager assetManager = createAssetManager(context, apk);
            return new Resources(assetManager, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
        }
    }

//    protected PluginPackageManager createPluginPackageManager() {
//        return new PluginPackageManager();
//    }
    
    public PluginContext createPluginContext(Context context) {
        if (context == null) {
            return new PluginContext(this);
        }
        
        return new PluginContext(this, context);
    }

    protected ResolveInfo chooseBestActivity(Intent intent, String s, int flags, List<ResolveInfo> query) {
        return query.get(0);
    }

    protected final String mLocation;
    protected PluginManager mPluginManager;
    //宿主context
    protected Context mHostContext;
    protected Context mPluginContext;
    protected final File mNativeLibDir;
    protected final PackageParser.Package mPackage;
    protected final PackageInfo mPackageInfo;
    protected Resources mResources;
    protected ClassLoader mClassLoader;//DexClassLoader，dexPath指定的是插件apk的路径
//    protected PluginPackageManager mPackageManager;

    protected Map<ComponentName, ActivityInfo> mActivityInfos;
    protected Map<ComponentName, ServiceInfo> mServiceInfos;
    protected Map<ComponentName, ActivityInfo> mReceiverInfos;
    protected Map<ComponentName, ProviderInfo> mProviderInfos;
    protected Map<String, ProviderInfo> mProviders; // key is authorities of provider
    protected Map<ComponentName, InstrumentationInfo> mInstrumentationInfos;

    protected Application mApplication;

    public LoadedPlugin(PluginManager pluginManager, Context context, File apk) throws Exception {
        this.mPluginManager = pluginManager;
        this.mHostContext = context;
        this.mLocation = apk.getAbsolutePath();
        this.mPackage = PackageParserCompat.parsePackage(context, apk, PackageParser.PARSE_MUST_BE_APK);
        this.mPackage.applicationInfo.metaData = this.mPackage.mAppMetaData;
        this.mPackageInfo = new PackageInfo();
        this.mPackageInfo.applicationInfo = this.mPackage.applicationInfo;
        this.mPackageInfo.applicationInfo.sourceDir = apk.getAbsolutePath();
    
        if (Build.VERSION.SDK_INT >= 28
            || (Build.VERSION.SDK_INT == 27 && Build.VERSION.PREVIEW_SDK_INT != 0)) { // Android P Preview
            try {
                this.mPackageInfo.signatures = this.mPackage.mSigningDetails.signatures;
            } catch (Throwable e) {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                this.mPackageInfo.signatures = info.signatures;
            }
        } else {
            this.mPackageInfo.signatures = this.mPackage.mSignatures;
        }
        
        this.mPackageInfo.packageName = this.mPackage.packageName;
        if (pluginManager.getLoadedPlugin(mPackageInfo.packageName) != null) {
            throw new RuntimeException("plugin has already been loaded : " + mPackageInfo.packageName);
        }
        this.mPackageInfo.versionCode = this.mPackage.mVersionCode;
        this.mPackageInfo.versionName = this.mPackage.mVersionName;
        this.mPackageInfo.permissions = new PermissionInfo[0];
        //hook packageManager
//        this.mPackageManager = createPluginPackageManager();
        this.mPluginContext = createPluginContext(null);
        this.mNativeLibDir = getDir(context, Constants.NATIVE_DIR);
        this.mPackage.applicationInfo.nativeLibraryDir = this.mNativeLibDir.getAbsolutePath();
        this.mResources = createResources(context, getPackageName(), apk);
        this.mClassLoader = createClassLoader(context, apk, this.mNativeLibDir, context.getClassLoader());

        tryToCopyNativeLib(apk);

        // Cache instrumentations
        Map<ComponentName, InstrumentationInfo> instrumentations = new HashMap<ComponentName, InstrumentationInfo>();
        for (PackageParser.Instrumentation instrumentation : this.mPackage.instrumentation) {
            instrumentations.put(instrumentation.getComponentName(), instrumentation.info);
        }
        this.mInstrumentationInfos = Collections.unmodifiableMap(instrumentations);
        this.mPackageInfo.instrumentation = instrumentations.values().toArray(new InstrumentationInfo[instrumentations.size()]);

        // Cache activities
        Map<ComponentName, ActivityInfo> activityInfos = new HashMap<ComponentName, ActivityInfo>();
        for (PackageParser.Activity activity : this.mPackage.activities) {
            activity.info.metaData = activity.metaData;
            activityInfos.put(activity.getComponentName(), activity.info);
        }
        this.mActivityInfos = Collections.unmodifiableMap(activityInfos);
        this.mPackageInfo.activities = activityInfos.values().toArray(new ActivityInfo[activityInfos.size()]);

        // Cache services
        Map<ComponentName, ServiceInfo> serviceInfos = new HashMap<ComponentName, ServiceInfo>();
        for (PackageParser.Service service : this.mPackage.services) {
            serviceInfos.put(service.getComponentName(), service.info);
        }
        this.mServiceInfos = Collections.unmodifiableMap(serviceInfos);
        this.mPackageInfo.services = serviceInfos.values().toArray(new ServiceInfo[serviceInfos.size()]);

        // Cache providers
        Map<String, ProviderInfo> providers = new HashMap<String, ProviderInfo>();
        Map<ComponentName, ProviderInfo> providerInfos = new HashMap<ComponentName, ProviderInfo>();
        for (PackageParser.Provider provider : this.mPackage.providers) {
            providers.put(provider.info.authority, provider.info);
            providerInfos.put(provider.getComponentName(), provider.info);
        }
        this.mProviders = Collections.unmodifiableMap(providers);
        this.mProviderInfos = Collections.unmodifiableMap(providerInfos);
        this.mPackageInfo.providers = providerInfos.values().toArray(new ProviderInfo[providerInfos.size()]);

        // 在宿主中动态注册广播
        Map<ComponentName, ActivityInfo> receivers = new HashMap<ComponentName, ActivityInfo>();
        for (PackageParser.Activity receiver : this.mPackage.receivers) {
            receivers.put(receiver.getComponentName(), receiver.info);

            //通过反射创建BroadcastReceiver实例，动态注册该广播
            //getClassLoader返回的是一个自定义的DexClassLoader，dexPath指向插件apk的路径,parent是宿主应用的ClassLoader
            BroadcastReceiver br = BroadcastReceiver.class.cast(getClassLoader().loadClass(receiver.getComponentName().getClassName()).newInstance());
            for (PackageParser.ActivityIntentInfo aii : receiver.intents) {
                //mHostContext是宿主应用的Context
                //将插件中静态注册的广播，动态注册到宿主应用中。
                this.mHostContext.registerReceiver(br, aii);
            }
        }
        this.mReceiverInfos = Collections.unmodifiableMap(receivers);
        this.mPackageInfo.receivers = receivers.values().toArray(new ActivityInfo[receivers.size()]);
    
        // try to invoke plugin's application
        invokeApplication();
    }

    protected void tryToCopyNativeLib(File apk) throws Exception {
        PluginUtil.copyNativeLib(apk, mHostContext, mPackageInfo, mNativeLibDir);
    }

    public String getLocation() {
        return this.mLocation;
    }

    public String getPackageName() {
        return this.mPackage.packageName;
    }

    private Field mPMField = null;
    private Object mProxyPm = null;

    public PackageManager getPackageManager() {
//        if(mProxyPm == null || mPMField == null){
//            try {
//                //1. 反射pm
//                Class ApplicationPackageManager = Class.forName("android.app.ApplicationPackageManager");
//                mPMField = ApplicationPackageManager.getDeclaredField("mPM");
//                mPMField.setAccessible(true);
//                //2. 动态代理pm
//                Class IPackageManagerClass = Class.forName("android.content.pm.IPackageManager");
//                PluginPackageManagerInvocationHandler mPackageManagerProxyhandler = new PluginPackageManagerInvocationHandler(mPluginManager, mPackageManager);
//                mProxyPm = Proxy.newProxyInstance(mHostContext.getClassLoader(), new Class[]{IPackageManagerClass}, mPackageManagerProxyhandler);
//                //3. 替换pm
//            } catch (Exception e) {
//                e.printStackTrace();
//                Log.e("lhwbest","反射1失败 : " + e.getMessage());
//            }
//
//        }
//        try {
//            mPMField.set(mHostContext.getPackageManager(), mProxyPm);
//        } catch (IllegalAccessException e) {
//            e.printStackTrace();
//            Log.e("lhwbest","反射2失败 : " + e.getMessage());
//        }
//
//        Log.e(TAG,"调用了LoadedPlugin 的 getPackageManager 方法");
//        PackageManager packageManager = mHostContext.getPackageManager();
//        return packageManager;
        return mHostContext.getPackageManager();
    }

    public AssetManager getAssets() {
        return getResources().getAssets();
    }

    public Resources getResources() {
        return this.mResources;
    }

    /**
     * 更新插件resource
     * 目前通过梳理代码，当加载新的插件会更新resource，并替换宿主和所有插件的resource
     *
     * @param newResources 最新的resrouces，包含宿主以及所有插件的资源
     */
    public void updateResources(Resources newResources) {
        this.mResources = newResources;
    }

    public ClassLoader getClassLoader() {
        return this.mClassLoader;
    }

    public PluginManager getPluginManager() {
        return this.mPluginManager;
    }

    public Context getHostContext() {
        return this.mHostContext;
    }

    public Context getPluginContext() {
        return this.mPluginContext;
    }

    public Application getApplication() {
        return mApplication;
    }

    /**
     *
     * @throws Exception
     *
     * 构造插件的application
     */
    public void invokeApplication() throws Exception {
        final Exception[] temp = new Exception[1];
        // make sure application's callback is run on ui thread.
        RunUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mApplication != null) {
                    return;
                }
                try {
                    mApplication = makeApplication(false, mPluginManager.getInstrumentation());
                } catch (Exception e) {
                    temp[0] = e;
                }
            }
        }, true);
        
        if (temp[0] != null) {
            throw temp[0];
        }
    }

    public String getPackageResourcePath() {
        int myUid = Process.myUid();
        ApplicationInfo appInfo = this.mPackage.applicationInfo;
        return appInfo.uid == myUid ? appInfo.sourceDir : appInfo.publicSourceDir;
    }

    public String getCodePath() {
        return this.mPackage.applicationInfo.sourceDir;
    }

    public Intent getLaunchIntent() {
        ContentResolver resolver = this.mPluginContext.getContentResolver();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        for (PackageParser.Activity activity : this.mPackage.activities) {
            for (PackageParser.ActivityIntentInfo intentInfo : activity.intents) {
                if (intentInfo.match(resolver, launcher, false, TAG) > 0) {
                    return Intent.makeMainActivity(activity.getComponentName());
                }
            }
        }

        return null;
    }

    public Intent getLeanbackLaunchIntent() {
        ContentResolver resolver = this.mPluginContext.getContentResolver();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);

        for (PackageParser.Activity activity : this.mPackage.activities) {
            for (PackageParser.ActivityIntentInfo intentInfo : activity.intents) {
                if (intentInfo.match(resolver, launcher, false, TAG) > 0) {
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setComponent(activity.getComponentName());
                    intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                    return intent;
                }
            }
        }

        return null;
    }

    public ApplicationInfo getApplicationInfo() {
        return this.mPackage.applicationInfo;
    }

    public PackageInfo getPackageInfo() {
        return this.mPackageInfo;
    }

    public ActivityInfo getActivityInfo(ComponentName componentName) {
        return this.mActivityInfos.get(componentName);
    }

    public ServiceInfo getServiceInfo(ComponentName componentName) {
        return this.mServiceInfos.get(componentName);
    }

    public ActivityInfo getReceiverInfo(ComponentName componentName) {
        return this.mReceiverInfos.get(componentName);
    }

    public ProviderInfo getProviderInfo(ComponentName componentName) {
        return this.mProviderInfos.get(componentName);
    }

    public Resources.Theme getTheme() {
        Resources.Theme theme = this.mResources.newTheme();
        theme.applyStyle(PluginUtil.selectDefaultTheme(this.mPackage.applicationInfo.theme, Build.VERSION.SDK_INT), false);
        return theme;
    }

    public void setTheme(int resid) {
        Reflector.QuietReflector.with(this.mResources).field("mThemeResId").set(resid);
    }

    /**
     * 构造插件的Application
     * @param forceDefaultAppClass 是否强制使用系统默认的Application.java类构造实例
     * @param instrumentation      宿主应用的instrumenttaion，也就是说插件使用宿主的Instrumenttaion
     * @return
     * @throws Exception
     */
    protected Application makeApplication(boolean forceDefaultAppClass, Instrumentation instrumentation) throws Exception {
        if (null != this.mApplication) {
            return this.mApplication;
        }

        //获取插件的Application Class
        String appClass = this.mPackage.applicationInfo.className;
        if (forceDefaultAppClass || null == appClass) {
            appClass = "android.app.Application";
        }

        //构造Application实例,并将PluginContext传入
        this.mApplication = instrumentation.newApplication(this.mClassLoader, appClass, this.getPluginContext());
        // inject activityLifecycleCallbacks of the host application
        mApplication.registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacksProxy());
        instrumentation.callApplicationOnCreate(this.mApplication);
        return this.mApplication;
    }

    public ResolveInfo resolveActivity(Intent intent, int flags) {
        List<ResolveInfo> query = this.queryIntentActivities(intent, flags);
        if (null == query || query.isEmpty()) {
            return null;
        }

        ContentResolver resolver = this.mPluginContext.getContentResolver();
        return chooseBestActivity(intent, intent.resolveTypeIfNeeded(resolver), flags, query);
    }

    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
        ComponentName component = intent.getComponent();
        List<ResolveInfo> resolveInfos = new ArrayList<ResolveInfo>();
        ContentResolver resolver = this.mPluginContext.getContentResolver();

        for (PackageParser.Activity activity : this.mPackage.activities) {
            if (match(activity, component)) {
                ResolveInfo resolveInfo = new ResolveInfo();
                resolveInfo.activityInfo = activity.info;
                resolveInfos.add(resolveInfo);
            } else if (component == null) {
                // only match implicit intent
                for (PackageParser.ActivityIntentInfo intentInfo : activity.intents) {
                    if (intentInfo.match(resolver, intent, true, TAG) >= 0) {
                        ResolveInfo resolveInfo = new ResolveInfo();
                        resolveInfo.activityInfo = activity.info;
                        resolveInfos.add(resolveInfo);
                        break;
                    }
                }
            }
        }

        return resolveInfos;
    }

    public ResolveInfo resolveService(Intent intent, int flags) {
        List<ResolveInfo> query = this.queryIntentServices(intent, flags);
        if (null == query || query.isEmpty()) {
            return null;
        }

        ContentResolver resolver = this.mPluginContext.getContentResolver();
        return chooseBestActivity(intent, intent.resolveTypeIfNeeded(resolver), flags, query);
    }

    public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
        ComponentName component = intent.getComponent();
        List<ResolveInfo> resolveInfos = new ArrayList<ResolveInfo>();
        ContentResolver resolver = this.mPluginContext.getContentResolver();

        for (PackageParser.Service service : this.mPackage.services) {
            if (match(service, component)) {
                ResolveInfo resolveInfo = new ResolveInfo();
                resolveInfo.serviceInfo = service.info;
                resolveInfos.add(resolveInfo);
            } else if (component == null) {
                // only match implicit intent
                for (PackageParser.ServiceIntentInfo intentInfo : service.intents) {
                    if (intentInfo.match(resolver, intent, true, TAG) >= 0) {
                        ResolveInfo resolveInfo = new ResolveInfo();
                        resolveInfo.serviceInfo = service.info;
                        resolveInfos.add(resolveInfo);
                        break;
                    }
                }
            }
        }

        return resolveInfos;
    }

    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
        ComponentName component = intent.getComponent();
        List<ResolveInfo> resolveInfos = new ArrayList<ResolveInfo>();
        ContentResolver resolver = this.mPluginContext.getContentResolver();

        for (PackageParser.Activity receiver : this.mPackage.receivers) {
            if (receiver.getComponentName().equals(component)) {
                ResolveInfo resolveInfo = new ResolveInfo();
                resolveInfo.activityInfo = receiver.info;
                resolveInfos.add(resolveInfo);
            } else if (component == null) {
                // only match implicit intent
                for (PackageParser.ActivityIntentInfo intentInfo : receiver.intents) {
                    if (intentInfo.match(resolver, intent, true, TAG) >= 0) {
                        ResolveInfo resolveInfo = new ResolveInfo();
                        resolveInfo.activityInfo = receiver.info;
                        resolveInfos.add(resolveInfo);
                        break;
                    }
                }
            }
        }

        return resolveInfos;
    }

    public ProviderInfo resolveContentProvider(String name, int flags) {
        return this.mProviders.get(name);
    }

    protected boolean match(PackageParser.Component component, ComponentName target) {
        ComponentName source = component.getComponentName();
        if (source == target) return true;
        if (source != null && target != null
                && source.getClassName().equals(target.getClassName())
                && (source.getPackageName().equals(target.getPackageName())
                || mHostContext.getPackageName().equals(target.getPackageName()))) {
            return true;
        }
        return false;
    }

    public static class PluginPackageManagerInvocationHandler implements InvocationHandler {
        private PluginManager mPluginManager;
        private Object mPm;

        public PluginPackageManagerInvocationHandler(PluginManager pluginManager, Object pm){
            this.mPluginManager = pluginManager;
            this.mPm = pm;
        }


        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if(methodName.equals("getPackageInfo")){
                String packageName = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if(args[0] instanceof VersionedPackage){
                        packageName = ((VersionedPackage)args[0]).getPackageName();
                    }
                }
                if(args[0] instanceof String){
                    packageName = String.valueOf(args[0]);
                }
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                if (null != plugin) {
                    return plugin.mPackageInfo;
                }
            }else if(methodName.equals("getLaunchIntentForPackage") || methodName.equals("getLeanbackLaunchIntentForPackage")){
                String packageName = (String) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                if (null != plugin) {
                    return plugin.getLaunchIntent();
                }
            }else if(methodName.equals("getApplicationInfo")){
                String packageName = (String) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                if (null != plugin) {
                    return plugin.getApplicationInfo();
                }
            }else if(methodName.equals("getActivityInfo")){
                ComponentName component = (ComponentName) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    return plugin.mActivityInfos.get(component);
                }
            }else if(methodName.equals("getReceiverInfo")){
                ComponentName component = (ComponentName) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    return plugin.mReceiverInfos.get(component);
                }
            }else if(methodName.equals("getServiceInfo")){
                ComponentName component = (ComponentName) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    return plugin.mServiceInfos.get(component);
                }
            }else if(methodName.equals("getProviderInfo")){
                ComponentName component = (ComponentName) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    return plugin.mProviderInfos.get(component);
                }
            }else if(methodName.equals("resolveActivity")){
                Intent intent = (Intent) args[0];
                int flags = (int) args[1];
                ResolveInfo resolveInfo = mPluginManager.resolveActivity(intent, flags);
                if (null != resolveInfo) {
                    return resolveInfo;
                }
            }else if(methodName.equals("queryIntentActivities")){
                Intent intent = (Intent) args[0];
                int flags = (int) args[1];

                ComponentName component = intent.getComponent();
                if (null == component) {
                    if (intent.getSelector() != null) {
                        intent = intent.getSelector();
                        component = intent.getComponent();
                    }
                }

                if (null != component) {
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                    if (null != plugin) {
                        ActivityInfo activityInfo = plugin.getActivityInfo(component);
                        if (activityInfo != null) {
                            ResolveInfo resolveInfo = new ResolveInfo();
                            resolveInfo.activityInfo = activityInfo;
                            return Arrays.asList(resolveInfo);
                        }
                    }
                }

                List<ResolveInfo> all = new ArrayList<ResolveInfo>();

                List<ResolveInfo> pluginResolveInfos = mPluginManager.queryIntentActivities(intent, flags);
                if (null != pluginResolveInfos && pluginResolveInfos.size() > 0) {
                    all.addAll(pluginResolveInfos);
                }

                List<ResolveInfo> hostResolveInfos = (List<ResolveInfo>) method.invoke(mPm,args);
                if (null != hostResolveInfos && hostResolveInfos.size() > 0) {
                    all.addAll(hostResolveInfos);
                }
                return all;
            }else if(methodName.equals("queryBroadcastReceivers")){
                Intent intent = (Intent) args[0];
                int flags = (int) args[1];

                ComponentName component = intent.getComponent();
                if (null == component) {
                    if (intent.getSelector() != null) {
                        intent = intent.getSelector();
                        component = intent.getComponent();
                    }
                }

                if (null != component) {
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                    if (null != plugin) {
                        ActivityInfo activityInfo = plugin.getReceiverInfo(component);
                        if (activityInfo != null) {
                            ResolveInfo resolveInfo = new ResolveInfo();
                            resolveInfo.activityInfo = activityInfo;
                            return Arrays.asList(resolveInfo);
                        }
                    }
                }

                List<ResolveInfo> all = new ArrayList<>();

                List<ResolveInfo> pluginResolveInfos = mPluginManager.queryBroadcastReceivers(intent, flags);
                if (null != pluginResolveInfos && pluginResolveInfos.size() > 0) {
                    all.addAll(pluginResolveInfos);
                }

                List<ResolveInfo> hostResolveInfos = (List<ResolveInfo>) method.invoke(mPm,args);
                if (null != hostResolveInfos && hostResolveInfos.size() > 0) {
                    all.addAll(hostResolveInfos);
                }

                return all;
            }else if(methodName.equals("resolveService")){
                Intent intent = (Intent) args[0];
                int flags = (int) args[1];

                ResolveInfo resolveInfo = mPluginManager.resolveService(intent, flags);
                if (null != resolveInfo) {
                    return resolveInfo;
                }
            }else if(methodName.equals("queryIntentServices")){
                Intent intent = (Intent) args[0];
                int flags = (int) args[1];

                ComponentName component = intent.getComponent();
                if (null == component) {
                    if (intent.getSelector() != null) {
                        intent = intent.getSelector();
                        component = intent.getComponent();
                    }
                }

                if (null != component) {
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                    if (null != plugin) {
                        ServiceInfo serviceInfo = plugin.getServiceInfo(component);
                        if (serviceInfo != null) {
                            ResolveInfo resolveInfo = new ResolveInfo();
                            resolveInfo.serviceInfo = serviceInfo;
                            return Arrays.asList(resolveInfo);
                        }
                    }
                }

                List<ResolveInfo> all = new ArrayList<ResolveInfo>();

                List<ResolveInfo> pluginResolveInfos = mPluginManager.queryIntentServices(intent, flags);
                if (null != pluginResolveInfos && pluginResolveInfos.size() > 0) {
                    all.addAll(pluginResolveInfos);
                }

                List<ResolveInfo> hostResolveInfos = (List<ResolveInfo>) method.invoke(mPm,args);
                if (null != hostResolveInfos && hostResolveInfos.size() > 0) {
                    all.addAll(hostResolveInfos);
                }

                return all;
            }else if(methodName.equals("resolveContentProvider")){
                String name = (String) args[0];
                int flags = (int) args[1];

                ProviderInfo providerInfo = mPluginManager.resolveContentProvider(name, flags);
                if (null != providerInfo) {
                    return providerInfo;
                }
            }else if(methodName.equals("getInstrumentationInfo")){
                ComponentName component = (ComponentName) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    return plugin.mInstrumentationInfos.get(component);
                }
            }else if(methodName.equals("getDrawable")){
                String packageName = (String) args[0];
                int resid = (int) args[1];

                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                if (null != plugin) {
                    return plugin.mResources.getDrawable(resid);
                }
            }else if(methodName.equals("getActivityIcon")){
                if(args[0] instanceof ComponentName){
                    ComponentName component = (ComponentName) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(plugin.mActivityInfos.get(component).icon);
                    }
                }else if(args[0] instanceof Intent){
                    Intent intent = (Intent) args[0];
                    ResolveInfo ri = mPluginManager.resolveActivity(intent);
                    if (null != ri) {
                        LoadedPlugin plugin = mPluginManager.getLoadedPlugin(ri.resolvePackageName);
                        return plugin.mResources.getDrawable(ri.activityInfo.icon);
                    }
                }
            }else if(methodName.equals("getActivityBanner")){
                if(args[0] instanceof ComponentName){
                    ComponentName component = (ComponentName) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(plugin.mActivityInfos.get(component).banner);
                    }
                }else if(args[0] instanceof Intent){
                    Intent intent = (Intent) args[0];
                    ResolveInfo ri = mPluginManager.resolveActivity(intent);
                    if (null != ri) {
                        LoadedPlugin plugin = mPluginManager.getLoadedPlugin(ri.resolvePackageName);
                        return plugin.mResources.getDrawable(ri.activityInfo.banner);
                    }
                }
            }else if(methodName.equals("getApplicationIcon")){
                if(args[0] instanceof ApplicationInfo){
                    ApplicationInfo info = (ApplicationInfo) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(info.packageName);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(info.icon);
                    }
                }else if(args[0] instanceof String){
                    String packageName = (String) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(plugin.mPackage.applicationInfo.icon);
                    }
                }
            }else if(methodName.equals("getApplicationBanner")){
                if(args[0] instanceof ApplicationInfo){
                    ApplicationInfo info = (ApplicationInfo) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(info.packageName);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(info.banner);
                    }
                }else if(args[0] instanceof String){
                    String packageName = (String) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(plugin.mPackage.applicationInfo.banner);
                    }
                }
            }else if(methodName.equals("getActivityLogo")){
                if(args[0] instanceof ComponentName){
                    ComponentName component = (ComponentName) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(plugin.mActivityInfos.get(component).logo);
                    }
                }else if(args[0] instanceof Intent){
                    Intent intent = (Intent) args[0];
                    ResolveInfo ri = mPluginManager.resolveActivity(intent);
                    if (null != ri) {
                        LoadedPlugin plugin = mPluginManager.getLoadedPlugin(ri.resolvePackageName);
                        return plugin.mResources.getDrawable(ri.activityInfo.logo);
                    }
                }
            }else if(methodName.equals("getApplicationLogo")){
                if(args[0] instanceof ApplicationInfo){
                    ApplicationInfo info = (ApplicationInfo) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(info.packageName);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(0 != info.logo ? info.logo : android.R.drawable.sym_def_app_icon);
                    }
                }else if(args[0] instanceof String){
                    String packageName = (String) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                    if (null != plugin) {
                        return plugin.mResources.getDrawable(0 != plugin.mPackage.applicationInfo.logo ? plugin.mPackage.applicationInfo.logo : android.R.drawable.sym_def_app_icon);
                    }
                }
            }else if(methodName.equals("getText")){
                String packageName = (String) args[0];
                int resid = (int) args[1];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                if (null != plugin) {
                    return plugin.mResources.getText(resid);
                }
            }else if(methodName.equals("getXml")){
                String packageName = (String) args[0];
                int resid = (int) args[1];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                if (null != plugin) {
                    return plugin.mResources.getXml(resid);
                }
            }else if(methodName.equals("getApplicationLabel")){
                ApplicationInfo info = (ApplicationInfo) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(info.packageName);
                if (null != plugin) {
                    try {
                        return plugin.mResources.getText(info.labelRes);
                    } catch (Resources.NotFoundException e) {
                        // ignored.
                    }
                }
            }else if(methodName.equals("getResourcesForActivity")){
                ComponentName component = (ComponentName) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    return plugin.mResources;
                }
            }else if(methodName.equals("getResourcesForApplication")){
                if(args[0] instanceof ApplicationInfo){
                    ApplicationInfo app = (ApplicationInfo) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(app.packageName);
                    if (null != plugin) {
                        return plugin.mResources;
                    }
                }else if(args[0] instanceof String){
                    String appPackageName = (String) args[0];
                    LoadedPlugin plugin = mPluginManager.getLoadedPlugin(appPackageName);
                    if (null != plugin) {
                        return plugin.mResources;
                    }
                }
            }else if(methodName.equals("setInstallerPackageName")){
                String targetPackage = (String) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(targetPackage);
                if (null != plugin) {
                    return null;
                }
            }else if(methodName.equals("getInstallerPackageName")){
                String packageName = (String) args[0];
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
                if (null != plugin) {
                    return mPluginManager.getHostContext().getPackageName();
                }
            }
            return method.invoke(mPm,args);
        }
    }

    /**
     * @author johnsonlee
     */
    public static class PluginPackageManager extends PackageManager {

        private PluginManager mPluginManager;
        private PackageManager mHostPackageManager;

        public PluginPackageManager(PluginManager pluginManager){
            this.mPluginManager = pluginManager;
            mHostPackageManager = pluginManager.getHostContext().getPackageManager();
        }

        @Override
        public PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的getPackageInfo方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.mPackageInfo;
            }
            return this.mHostPackageManager.getPackageInfo(packageName, flags);
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public PackageInfo getPackageInfo(VersionedPackage versionedPackage, int i) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的getPackageInfo方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(versionedPackage.getPackageName());
            if (null != plugin) {
                return plugin.mPackageInfo;
            }

            return this.mHostPackageManager.getPackageInfo(versionedPackage, i);
        }

        @Override
        public String[] currentToCanonicalPackageNames(String[] names) {
            Log.e("lhwbest","调用了PluginPackageManager的currentToCanonicalPackageNames方法!!!!!!");
            return this.mHostPackageManager.currentToCanonicalPackageNames(names);
        }

        @Override
        public String[] canonicalToCurrentPackageNames(String[] names) {
            Log.e("lhwbest","调用了PluginPackageManager的canonicalToCurrentPackageNames方法!!!!!!");
            return this.mHostPackageManager.canonicalToCurrentPackageNames(names);
        }

        @Override
        public Intent getLaunchIntentForPackage(@NonNull String packageName) {
            Log.e("lhwbest","调用了PluginPackageManager的getLaunchIntentForPackage方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.getLaunchIntent();
            }

            return this.mHostPackageManager.getLaunchIntentForPackage(packageName);
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public Intent getLeanbackLaunchIntentForPackage(@NonNull String packageName) {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.getLeanbackLaunchIntent();
            }

            return this.mHostPackageManager.getLeanbackLaunchIntentForPackage(packageName);
        }

        @Override
        public int[] getPackageGids(@NonNull String packageName) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.getPackageGids(packageName);
        }

        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public int[] getPackageGids(String packageName, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.getPackageGids(packageName, flags);
        }

        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public int getPackageUid(String packageName, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.getPackageUid(packageName, flags);
        }

        @Override
        public PermissionInfo getPermissionInfo(String name, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.getPermissionInfo(name, flags);
        }

        @Override
        public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.queryPermissionsByGroup(group, flags);
        }

        @Override
        public PermissionGroupInfo getPermissionGroupInfo(String name, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.getPermissionGroupInfo(name, flags);
        }

        @Override
        public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.getAllPermissionGroups(flags);
        }

        @Override
        public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.getApplicationInfo();
            }

            return this.mHostPackageManager.getApplicationInfo(packageName, flags);
        }

        @Override
        public ActivityInfo getActivityInfo(ComponentName component, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mActivityInfos.get(component);
            }

            return this.mHostPackageManager.getActivityInfo(component, flags);
        }

        @Override
        public ActivityInfo getReceiverInfo(ComponentName component, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mReceiverInfos.get(component);
            }

            return this.mHostPackageManager.getReceiverInfo(component, flags);
        }

        @Override
        public ServiceInfo getServiceInfo(ComponentName component, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mServiceInfos.get(component);
            }

            return this.mHostPackageManager.getServiceInfo(component, flags);
        }

        @Override
        public ProviderInfo getProviderInfo(ComponentName component, int flags) throws NameNotFoundException {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mProviderInfos.get(component);
            }

            return this.mHostPackageManager.getProviderInfo(component, flags);
        }

        @Override
        public List<PackageInfo> getInstalledPackages(int flags) {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.getInstalledPackages(flags);
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
        @Override
        public List<PackageInfo> getPackagesHoldingPermissions(String[] permissions, int flags) {
            Log.e("lhwbest","调用了PluginPackageManager的方法!!!!!!");
            return this.mHostPackageManager.getPackagesHoldingPermissions(permissions, flags);
        }

        @Override
        public int checkPermission(String permName, String pkgName) {
            return this.mHostPackageManager.checkPermission(permName, pkgName);
        }

        @TargetApi(Build.VERSION_CODES.M)
        @Override
        public boolean isPermissionRevokedByPolicy(@NonNull String permName, @NonNull String pkgName) {
            return this.mHostPackageManager.isPermissionRevokedByPolicy(permName, pkgName);
        }

        @Override
        public boolean addPermission(PermissionInfo info) {
            return this.mHostPackageManager.addPermission(info);
        }

        @Override
        public boolean addPermissionAsync(PermissionInfo info) {
            return this.mHostPackageManager.addPermissionAsync(info);
        }

        @Override
        public void removePermission(String name) {
            this.mHostPackageManager.removePermission(name);
        }

        @Override
        public int checkSignatures(String pkg1, String pkg2) {
            return this.mHostPackageManager.checkSignatures(pkg1, pkg2);
        }

        @Override
        public int checkSignatures(int uid1, int uid2) {
            return this.mHostPackageManager.checkSignatures(uid1, uid2);
        }

        @Override
        public String[] getPackagesForUid(int uid) {
            return this.mHostPackageManager.getPackagesForUid(uid);
        }

        @Override
        public String getNameForUid(int uid) {
            return this.mHostPackageManager.getNameForUid(uid);
        }

        @Override
        public List<ApplicationInfo> getInstalledApplications(int flags) {
            return this.mHostPackageManager.getInstalledApplications(flags);
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public boolean isInstantApp() {
            return this.mHostPackageManager.isInstantApp();
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public boolean isInstantApp(String packageName) {
            return this.mHostPackageManager.isInstantApp(packageName);
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public int getInstantAppCookieMaxBytes() {
            return this.mHostPackageManager.getInstantAppCookieMaxBytes();
        }

        @TargetApi(Build.VERSION_CODES.O)
        @NonNull
        @Override
        public byte[] getInstantAppCookie() {
            return this.mHostPackageManager.getInstantAppCookie();
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public void clearInstantAppCookie() {
            this.mHostPackageManager.clearInstantAppCookie();
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public void updateInstantAppCookie(@Nullable byte[] cookie) {
            this.mHostPackageManager.updateInstantAppCookie(cookie);
        }

        @Override
        public String[] getSystemSharedLibraryNames() {
            return this.mHostPackageManager.getSystemSharedLibraryNames();
        }

        @TargetApi(Build.VERSION_CODES.O)
        @NonNull
        @Override
        public List<SharedLibraryInfo> getSharedLibraries(int flags) {
            return this.mHostPackageManager.getSharedLibraries(flags);
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Nullable
        @Override
        public ChangedPackages getChangedPackages(int sequenceNumber) {
            return this.mHostPackageManager.getChangedPackages(sequenceNumber);
        }

        @Override
        public FeatureInfo[] getSystemAvailableFeatures() {
            return this.mHostPackageManager.getSystemAvailableFeatures();
        }

        @Override
        public boolean hasSystemFeature(String name) {
            return this.mHostPackageManager.hasSystemFeature(name);
        }

        @TargetApi(Build.VERSION_CODES.N)
        @Override
        public boolean hasSystemFeature(String name, int version) {
            return this.mHostPackageManager.hasSystemFeature(name, version);
        }

        @Override
        public ResolveInfo resolveActivity(Intent intent, int flags) {
            ResolveInfo resolveInfo = mPluginManager.resolveActivity(intent, flags);
            if (null != resolveInfo) {
                return resolveInfo;
            }

            return this.mHostPackageManager.resolveActivity(intent, flags);
        }

        @Override
        public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
            ComponentName component = intent.getComponent();
            if (null == component) {
                if (intent.getSelector() != null) {
                    intent = intent.getSelector();
                    component = intent.getComponent();
                }
            }

            if (null != component) {
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    ActivityInfo activityInfo = plugin.getActivityInfo(component);
                    if (activityInfo != null) {
                        ResolveInfo resolveInfo = new ResolveInfo();
                        resolveInfo.activityInfo = activityInfo;
                        return Arrays.asList(resolveInfo);
                    }
                }
            }

            List<ResolveInfo> all = new ArrayList<ResolveInfo>();

            List<ResolveInfo> pluginResolveInfos = mPluginManager.queryIntentActivities(intent, flags);
            if (null != pluginResolveInfos && pluginResolveInfos.size() > 0) {
                all.addAll(pluginResolveInfos);
            }

            List<ResolveInfo> hostResolveInfos = this.mHostPackageManager.queryIntentActivities(intent, flags);
            if (null != hostResolveInfos && hostResolveInfos.size() > 0) {
                all.addAll(hostResolveInfos);
            }

            return all;
        }

        @Override
        public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller, Intent[] specifics, Intent intent, int flags) {
            return this.mHostPackageManager.queryIntentActivityOptions(caller, specifics, intent, flags);
        }

        @Override
        public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags) {
            ComponentName component = intent.getComponent();
            if (null == component) {
                if (intent.getSelector() != null) {
                    intent = intent.getSelector();
                    component = intent.getComponent();
                }
            }

            if (null != component) {
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    ActivityInfo activityInfo = plugin.getReceiverInfo(component);
                    if (activityInfo != null) {
                        ResolveInfo resolveInfo = new ResolveInfo();
                        resolveInfo.activityInfo = activityInfo;
                        return Arrays.asList(resolveInfo);
                    }
                }
            }

            List<ResolveInfo> all = new ArrayList<>();

            List<ResolveInfo> pluginResolveInfos = mPluginManager.queryBroadcastReceivers(intent, flags);
            if (null != pluginResolveInfos && pluginResolveInfos.size() > 0) {
                all.addAll(pluginResolveInfos);
            }

            List<ResolveInfo> hostResolveInfos = this.mHostPackageManager.queryBroadcastReceivers(intent, flags);
            if (null != hostResolveInfos && hostResolveInfos.size() > 0) {
                all.addAll(hostResolveInfos);
            }

            return all;
        }

        @Override
        public ResolveInfo resolveService(Intent intent, int flags) {
            ResolveInfo resolveInfo = mPluginManager.resolveService(intent, flags);
            if (null != resolveInfo) {
                return resolveInfo;
            }

            return this.mHostPackageManager.resolveService(intent, flags);
        }

        @Override
        public List<ResolveInfo> queryIntentServices(Intent intent, int flags) {
            ComponentName component = intent.getComponent();
            if (null == component) {
                if (intent.getSelector() != null) {
                    intent = intent.getSelector();
                    component = intent.getComponent();
                }
            }

            if (null != component) {
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
                if (null != plugin) {
                    ServiceInfo serviceInfo = plugin.getServiceInfo(component);
                    if (serviceInfo != null) {
                        ResolveInfo resolveInfo = new ResolveInfo();
                        resolveInfo.serviceInfo = serviceInfo;
                        return Arrays.asList(resolveInfo);
                    }
                }
            }

            List<ResolveInfo> all = new ArrayList<ResolveInfo>();

            List<ResolveInfo> pluginResolveInfos = mPluginManager.queryIntentServices(intent, flags);
            if (null != pluginResolveInfos && pluginResolveInfos.size() > 0) {
                all.addAll(pluginResolveInfos);
            }

            List<ResolveInfo> hostResolveInfos = this.mHostPackageManager.queryIntentServices(intent, flags);
            if (null != hostResolveInfos && hostResolveInfos.size() > 0) {
                all.addAll(hostResolveInfos);
            }

            return all;
        }

        @Override
        @TargetApi(Build.VERSION_CODES.KITKAT)
        public List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags) {
            return this.mHostPackageManager.queryIntentContentProviders(intent, flags);
        }

        @Override
        public ProviderInfo resolveContentProvider(String name, int flags) {
            ProviderInfo providerInfo = mPluginManager.resolveContentProvider(name, flags);
            if (null != providerInfo) {
                return providerInfo;
            }

            return this.mHostPackageManager.resolveContentProvider(name, flags);
        }

        @Override
        public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags) {
            return this.mHostPackageManager.queryContentProviders(processName, uid, flags);
        }

        @Override
        public InstrumentationInfo getInstrumentationInfo(ComponentName component, int flags) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mInstrumentationInfos.get(component);
            }

            return this.mHostPackageManager.getInstrumentationInfo(component, flags);
        }

        @Override
        public List<InstrumentationInfo> queryInstrumentation(String targetPackage, int flags) {
            return this.mHostPackageManager.queryInstrumentation(targetPackage, flags);
        }

        @Override
        public Drawable getDrawable(String packageName, int resid, ApplicationInfo appInfo) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.mResources.getDrawable(resid);
            }

            return this.mHostPackageManager.getDrawable(packageName, resid, appInfo);
        }

        @Override
        public Drawable getActivityIcon(ComponentName component) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mResources.getDrawable(plugin.mActivityInfos.get(component).icon);
            }

            return this.mHostPackageManager.getActivityIcon(component);
        }

        @Override
        public Drawable getActivityIcon(Intent intent) throws NameNotFoundException {
            ResolveInfo ri = mPluginManager.resolveActivity(intent);
            if (null != ri) {
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(ri.resolvePackageName);
                return plugin.mResources.getDrawable(ri.activityInfo.icon);
            }

            return this.mHostPackageManager.getActivityIcon(intent);
        }

        @TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
        @Override
        public Drawable getActivityBanner(ComponentName component) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mResources.getDrawable(plugin.mActivityInfos.get(component).banner);
            }

            return this.mHostPackageManager.getActivityBanner(component);
        }

        @TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
        @Override
        public Drawable getActivityBanner(Intent intent) throws NameNotFoundException {
            ResolveInfo ri = mPluginManager.resolveActivity(intent);
            if (null != ri) {
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(ri.resolvePackageName);
                return plugin.mResources.getDrawable(ri.activityInfo.banner);
            }

            return this.mHostPackageManager.getActivityBanner(intent);
        }

        @Override
        public Drawable getDefaultActivityIcon() {
            return this.mHostPackageManager.getDefaultActivityIcon();
        }

        @Override
        public Drawable getApplicationIcon(ApplicationInfo info) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(info.packageName);
            if (null != plugin) {
                return plugin.mResources.getDrawable(info.icon);
            }

            return this.mHostPackageManager.getApplicationIcon(info);
        }

        @Override
        public Drawable getApplicationIcon(String packageName) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.mResources.getDrawable(plugin.mPackage.applicationInfo.icon);
            }

            return this.mHostPackageManager.getApplicationIcon(packageName);
        }

        @TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
        @Override
        public Drawable getApplicationBanner(ApplicationInfo info) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(info.packageName);
            if (null != plugin) {
                return plugin.mResources.getDrawable(info.banner);
            }

            return this.mHostPackageManager.getApplicationBanner(info);
        }

        @TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
        @Override
        public Drawable getApplicationBanner(String packageName) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.mResources.getDrawable(plugin.mPackage.applicationInfo.banner);
            }

            return this.mHostPackageManager.getApplicationBanner(packageName);
        }

        @Override
        public Drawable getActivityLogo(ComponentName component) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mResources.getDrawable(plugin.mActivityInfos.get(component).logo);
            }

            return this.mHostPackageManager.getActivityLogo(component);
        }

        @Override
        public Drawable getActivityLogo(Intent intent) throws NameNotFoundException {
            ResolveInfo ri = mPluginManager.resolveActivity(intent);
            if (null != ri) {
                LoadedPlugin plugin = mPluginManager.getLoadedPlugin(ri.resolvePackageName);
                return plugin.mResources.getDrawable(ri.activityInfo.logo);
            }

            return this.mHostPackageManager.getActivityLogo(intent);
        }

        @Override
        public Drawable getApplicationLogo(ApplicationInfo info) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(info.packageName);
            if (null != plugin) {
                return plugin.mResources.getDrawable(0 != info.logo ? info.logo : android.R.drawable.sym_def_app_icon);
            }

            return this.mHostPackageManager.getApplicationLogo(info);
        }

        @Override
        public Drawable getApplicationLogo(String packageName) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.mResources.getDrawable(0 != plugin.mPackage.applicationInfo.logo ? plugin.mPackage.applicationInfo.logo : android.R.drawable.sym_def_app_icon);
            }

            return this.mHostPackageManager.getApplicationLogo(packageName);
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public Drawable getUserBadgedIcon(Drawable icon, UserHandle user) {
            return this.mHostPackageManager.getUserBadgedIcon(icon, user);
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public Drawable getUserBadgeForDensity(UserHandle user, int density) {
            try {
                return Reflector.with(this.mHostPackageManager)
                    .method("getUserBadgeForDensity", UserHandle.class, int.class)
                    .call(user, density);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public Drawable getUserBadgedDrawableForDensity(Drawable drawable, UserHandle user, Rect badgeLocation, int badgeDensity) {
            return this.mHostPackageManager.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity);
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public CharSequence getUserBadgedLabel(CharSequence label, UserHandle user) {
            return this.mHostPackageManager.getUserBadgedLabel(label, user);
        }

        @Override
        public CharSequence getText(String packageName, int resid, ApplicationInfo appInfo) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.mResources.getText(resid);
            }

            return this.mHostPackageManager.getText(packageName, resid, appInfo);
        }

        @Override
        public XmlResourceParser getXml(String packageName, int resid, ApplicationInfo appInfo) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return plugin.mResources.getXml(resid);
            }

            return this.mHostPackageManager.getXml(packageName, resid, appInfo);
        }

        @Override
        public CharSequence getApplicationLabel(ApplicationInfo info) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(info.packageName);
            if (null != plugin) {
                try {
                    return plugin.mResources.getText(info.labelRes);
                } catch (Resources.NotFoundException e) {
                    // ignored.
                }
            }

            return this.mHostPackageManager.getApplicationLabel(info);
        }

        @Override
        public Resources getResourcesForActivity(ComponentName component) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(component);
            if (null != plugin) {
                return plugin.mResources;
            }

            return this.mHostPackageManager.getResourcesForActivity(component);
        }

        @Override
        public Resources getResourcesForApplication(ApplicationInfo app) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(app.packageName);
            if (null != plugin) {
                return plugin.mResources;
            }

            return this.mHostPackageManager.getResourcesForApplication(app);
        }

        @Override
        public Resources getResourcesForApplication(String appPackageName) throws NameNotFoundException {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(appPackageName);
            if (null != plugin) {
                return plugin.mResources;
            }

            return this.mHostPackageManager.getResourcesForApplication(appPackageName);
        }

        @Override
        public void verifyPendingInstall(int id, int verificationCode) {
            this.mHostPackageManager.verifyPendingInstall(id, verificationCode);
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        @Override
        public void extendVerificationTimeout(int id, int verificationCodeAtTimeout, long millisecondsToDelay) {
            this.mHostPackageManager.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsToDelay);
        }

        @Override
        public void setInstallerPackageName(String targetPackage, String installerPackageName) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(targetPackage);
            if (null != plugin) {
                return;
            }

            this.mHostPackageManager.setInstallerPackageName(targetPackage, installerPackageName);
        }

        @Override
        public String getInstallerPackageName(String packageName) {
            LoadedPlugin plugin = mPluginManager.getLoadedPlugin(packageName);
            if (null != plugin) {
                return mPluginManager.getHostContext().getPackageName();
            }

            return this.mHostPackageManager.getInstallerPackageName(packageName);
        }

        @Override
        public void addPackageToPreferred(String packageName) {
            this.mHostPackageManager.addPackageToPreferred(packageName);
        }

        @Override
        public void removePackageFromPreferred(String packageName) {
            this.mHostPackageManager.removePackageFromPreferred(packageName);
        }

        @Override
        public List<PackageInfo> getPreferredPackages(int flags) {
            return this.mHostPackageManager.getPreferredPackages(flags);
        }

        @Override
        public void addPreferredActivity(IntentFilter filter, int match, ComponentName[] set, ComponentName activity) {
            this.mHostPackageManager.addPreferredActivity(filter, match, set, activity);
        }

        @Override
        public void clearPackagePreferredActivities(String packageName) {
            this.mHostPackageManager.clearPackagePreferredActivities(packageName);
        }

        @Override
        public int getPreferredActivities(@NonNull List<IntentFilter> outFilters, @NonNull List<ComponentName> outActivities, String packageName) {
            return this.mHostPackageManager.getPreferredActivities(outFilters, outActivities, packageName);
        }

        @Override
        public void setComponentEnabledSetting(ComponentName component, int newState, int flags) {
            this.mHostPackageManager.setComponentEnabledSetting(component, newState, flags);
        }

        @Override
        public int getComponentEnabledSetting(ComponentName component) {
            return this.mHostPackageManager.getComponentEnabledSetting(component);
        }

        @Override
        public void setApplicationEnabledSetting(String packageName, int newState, int flags) {
            this.mHostPackageManager.setApplicationEnabledSetting(packageName, newState, flags);
        }

        @Override
        public int getApplicationEnabledSetting(String packageName) {
            return this.mHostPackageManager.getApplicationEnabledSetting(packageName);
        }

        @Override
        public boolean isSafeMode() {
            return this.mHostPackageManager.isSafeMode();
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public void setApplicationCategoryHint(@NonNull String packageName, int categoryHint) {
            this.mHostPackageManager.setApplicationCategoryHint(packageName, categoryHint);
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public @NonNull PackageInstaller getPackageInstaller() {
            return this.mHostPackageManager.getPackageInstaller();
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public boolean canRequestPackageInstalls() {
            return this.mHostPackageManager.canRequestPackageInstalls();
        }

        public Drawable loadItemIcon(PackageItemInfo itemInfo, ApplicationInfo appInfo) {
            if (itemInfo == null) {
                return null;
            }
            return itemInfo.loadIcon(this.mHostPackageManager);
        }
    }

}
