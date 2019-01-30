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

/**
 * Created by renyugang on 16/8/15.
 */
public class Constants {
    public static final String KEY_IS_PLUGIN = "isPlugin";
    public static final String KEY_TARGET_PACKAGE = "target.package";
    public static final String KEY_TARGET_ACTIVITY = "target.activity";

    public static final String OPTIMIZE_DIR = "dex";
    public static final String NATIVE_DIR = "valibs";

    //将插件的资源全部添加到宿主的 Resources
    public static final boolean COMBINE_RESOURCES = true;
    //宿主和插件有同一个类(包名、类名相同)， 如果COMBINE_CLASSLOADER为true则插件会加载宿主中的类
    public static final boolean COMBINE_CLASSLOADER = true;
    public static final boolean DEBUG = true;
    
    public static final String TAG = "VA";
    public static final String TAG_PREFIX = TAG + ".";

}
