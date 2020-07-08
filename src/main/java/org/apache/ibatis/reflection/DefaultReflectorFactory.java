/**
 *    Copyright 2009-2015 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * DefaultReflectorFactory 用于创建 Reflector，同时兼有缓存的功能
 */
public class DefaultReflectorFactory implements ReflectorFactory {
  private boolean classCacheEnabled = true;
  /** 目标类和反射器映射缓存*/
  private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<Class<?>, Reflector>();

  public DefaultReflectorFactory() {
  }

  @Override
  public boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }

  @Override
  public void setClassCacheEnabled(boolean classCacheEnabled) {
    this.classCacheEnabled = classCacheEnabled;
  }

  @Override
  public Reflector findForClass(Class<?> type) {
    // classCacheEnabled 默认为 true
    if (classCacheEnabled) {
            // synchronized (type) removed see issue #461
      // 从缓存中获取 Reflector 对象
      Reflector cached = reflectorMap.get(type);
      // 缓存为空，则创建一个新的 Reflector 实例，并放入缓存中
      if (cached == null) {
        cached = new Reflector(type);
        reflectorMap.put(type, cached);
      }
      return cached;
    } else {
      // 创建一个新的 Reflector 实例
      return new Reflector(type);
    }
  }

}
