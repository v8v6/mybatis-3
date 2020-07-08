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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ReflectPermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/*
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 */
/**
 * @author Clinton Begin
 * 通过反射获取目标类的 getter 方法及其返回值类型，
 * setter 方法及其参数值类型等元信息
 */
public class Reflector {

  private static final String[] EMPTY_STRING_ARRAY = new String[0];

  private Class<?> type;
  /** 可读属性名称数组，用于保存 getter 方法对应的属性名称*/
  private String[] readablePropertyNames = EMPTY_STRING_ARRAY;
  /** 可写属性名称数组，用于保存 setter 方法对应的属性名称*/
  private String[] writeablePropertyNames = EMPTY_STRING_ARRAY;
  /** 用于保存属性名称到 Invoke 的映射。 setter 方法会被封装到 MethodInvoker 对象中*/
  private Map<String, Invoker> setMethods = new HashMap<String, Invoker>();
  /** 用于保存属性名称到 Invoke 的映射。同上， getter 方法也会被封装到 MethodInvoker 对象中*/
  private Map<String, Invoker> getMethods = new HashMap<String, Invoker>();
  /** 用于保存 setter 对应的属性名与参数类型的映射*/
  private Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>();
  /** 用于保存 getter 对应的属性名与参数类型的映射*/
  private Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>();
  /** 构造器*/
  private Constructor<?> defaultConstructor;

  /** 用于保存大写属性名与属性名之间的映射，比如 <NAME,name>*/
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

  public Reflector(Class<?> clazz) {
    type = clazz;
    // 解析目标类的默认构造方法，并赋值给 defaultConstructor 变量
    addDefaultConstructor(clazz);
    // 解析 getter 方法，并将解析结果放入 getMethods 中
    addGetMethods(clazz);
    // 解析 setter 方法，并将解析结果放入 setMethods 中
    addSetMethods(clazz);
    // 解析属性字段，并将解析结果添加到 setMethods 或 getMethods 中
    addFields(clazz);
    // 从 getMethods 映射中获取可读属性名数组
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    // 从 setMethods 映射中获取可写属性名数组
    writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    // 将所有属性名的大写形式作为键，属性名作为值，存入到 caseInsensitivePropertyMap 中
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writeablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    for (Constructor<?> constructor : consts) {
      if (constructor.getParameterTypes().length == 0) {
        if (canAccessPrivateMethods()) {
          try {
            constructor.setAccessible(true);
          } catch (Exception e) {
            // Ignored. This is only a final precaution, nothing we can do.
          }
        }
        if (constructor.isAccessible()) {
          this.defaultConstructor = constructor;
        }
      }
    }
  }

  private void addGetMethods(Class<?> cls) {
    // 冲突的方法
    Map<String, List<Method>> conflictingGetters = new HashMap<String, List<Method>>();
    // 获取当前类，接口，以及父类中的方法。
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      String name = method.getName();
      // 过滤出以 get 或 is 开头的方法,并且方法的参数是没有的
      // 将 getXXX 或 isXXX 等方法名转成相应的属性， 比如 getName -> name, isFlag -》 flag
      if (name.startsWith("get") && name.length() > 3) {
        if (method.getParameterTypes().length == 0) {
          name = PropertyNamer.methodToProperty(name);
          /*
           * 将冲突的方法添加到 conflictingGetters 中。考虑这样一种情况：
           * getTitle 和 isTitle 两个方法经过 methodToProperty 处理，
           * 均得到 name = title，这会导致冲突。
           * 对于冲突的方法，这里先统一起存起来，后续再解决冲突
           */
          addMethodConflict(conflictingGetters, name, method);
        }
      } else if (name.startsWith("is") && name.length() > 2) {
        if (method.getParameterTypes().length == 0) {
          name = PropertyNamer.methodToProperty(name);
          addMethodConflict(conflictingGetters, name, method);
        }
      }
    }
    // 解决 getter 冲突
    resolveGetterConflicts(conflictingGetters);
  }

  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    // {"title":[getTitle,isTitle]}
    for (String propName : conflictingGetters.keySet()) {
      // 获取多个字段对应的getter方法
      List<Method> getters = conflictingGetters.get(propName);
      Iterator<Method> iterator = getters.iterator();
      Method firstMethod = iterator.next();
      // 只存在一个，直接添加
      if (getters.size() == 1) {
        // 将筛选出的方法添加到 getMethods 中，并将方法返回值添加到 getTypes 中
        addGetMethod(propName, firstMethod);
      } else {
        Method getter = firstMethod;
        // 获取返回值类型
        Class<?> getterType = firstMethod.getReturnType();
        // 集合中还有其他Method
        while (iterator.hasNext()) {
          Method method = iterator.next();
          // 获取返回值类型
          Class<?> methodType = method.getReturnType();
          /*
           * 两个方法的返回值类型一致，若两个方法返回值类型均为 boolean，
           * 无法决定哪个方法更为合适，只能抛出异常
           */
          if (methodType.equals(getterType)) {
            throw new ReflectionException("Illegal overloaded getter method with ambiguous type for property "
                + propName + " in class " + firstMethod.getDeclaringClass()
                + ".  This breaks the JavaBeans " + "specification and can cause unpredicatble results.");

          } else if (methodType.isAssignableFrom(getterType)) {
            // OK getter type is descendant
            /*
             * getterType 是 methodType 的子类，类型上更为具体，
             * 则认为当前的 getter 仍是合适的，无需做什么事情
             */
          } else if (getterType.isAssignableFrom(methodType)) {
            /*
             * methodType 是 getterType 的子类，类型上更为具体，
             * 则认为当前的 method 是合适的
             */
            getter = method;
            getterType = methodType;
          } else {
            throw new ReflectionException("Illegal overloaded getter method with ambiguous type for property "
                + propName + " in class " + firstMethod.getDeclaringClass()
                + ".  This breaks the JavaBeans " + "specification and can cause unpredicatble results.");
          }
        }
        addGetMethod(propName, getter);
      }
    }
  }

  private void addGetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      getMethods.put(name, new MethodInvoker(method));
      getTypes.put(name, method.getReturnType());
    }
  }

  private void addSetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingSetters = new HashMap<String, List<Method>>();
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      String name = method.getName();
      // 过滤出 setter 方法，且方法仅有一个参数
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {
          name = PropertyNamer.methodToProperty(name);
          // setter 方法发生冲突原因是：可能存在重载情况，比如：
          // void setSex(int sex);
          // void setSex(SexEnum sex);
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    // 解决 setter 冲突
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.get(name);
    if (list == null) {
      list = new ArrayList<Method>();
      conflictingMethods.put(name, list);
    }
    list.add(method);
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      Method firstMethod = setters.get(0);
      // 只有一个，直接处理
      if (setters.size() == 1) {
        // 将筛选出的方法放入 setMethods 中，并将方法参数值添加到 setTypes 中
        addSetMethod(propName, firstMethod);
      } else {
        /*
         * 获取 getter 方法的返回值类型，由于 getter 方法不存在重载的情况，
         * 所以可以用它的返回值类型反推哪个 setter 的更为合适
         */
        Class<?> expectedType = getTypes.get(propName);
        if (expectedType == null) {
          throw new ReflectionException("Illegal overloaded setter method with ambiguous type for property "
              + propName + " in class " + firstMethod.getDeclaringClass() + ".  This breaks the JavaBeans " +
              "specification and can cause unpredicatble results.");
        } else {
          Iterator<Method> methods = setters.iterator();
          Method setter = null;
          while (methods.hasNext()) {
            Method method = methods.next();
            // 参数类型和返回类型一致，则结束循环
            if (method.getParameterTypes().length == 1
                && expectedType.equals(method.getParameterTypes()[0])) {
              setter = method;
              break;
            }
          }
          if (setter == null) {
            throw new ReflectionException("Illegal overloaded setter method with ambiguous type for property "
                + propName + " in class " + firstMethod.getDeclaringClass() + ".  This breaks the JavaBeans " +
                "specification and can cause unpredicatble results.");
          }
          // 将筛选出的方法放入 setMethods 中，并将方法参数值添加到 setTypes 中
          addSetMethod(propName, setter);
        }
      }
    }
  }

  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      setTypes.put(name, method.getParameterTypes()[0]);
    }
  }

  private void addFields(Class<?> clazz) {
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      if (canAccessPrivateMethods()) {
        try {
          field.setAccessible(true);
        } catch (Exception e) {
          // Ignored. This is only a final precaution, nothing we can do.
        }
      }
      if (field.isAccessible()) {
        if (!setMethods.containsKey(field.getName())) {
          // issue #379 - removed the check for final because JDK 1.5 allows
          // modification of final fields through reflection (JSR-133). (JGB)
          // pr #16 - final static can only be set by the classloader
          int modifiers = field.getModifiers();
          if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
            addSetField(field);
          }
        }
        if (!getMethods.containsKey(field.getName())) {
          addGetField(field);
        }
      }
    }
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      setTypes.put(field.getName(), field.getType());
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      getTypes.put(field.getName(), field.getType());
    }
  }

  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /*
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler Class.getMethods(),
   * because we want to look for private methods as well.
   *
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
    Map<String, Method> uniqueMethods = new HashMap<String, Method>();
    Class<?> currentClass = cls;
    while (currentClass != null) {
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[methods.size()]);
  }

  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        if (!uniqueMethods.containsKey(signature)) {
          if (canAccessPrivateMethods()) {
            try {
              currentMethod.setAccessible(true);
            } catch (Exception e) {
              // Ignored. This is only a final precaution, nothing we can do.
            }
          }

          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    return sb.toString();
  }

  private static boolean canAccessPrivateMethods() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /*
   * Gets the name of the class the instance provides information for
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /*
   * Gets the type for a property setter
   *
   * @param propertyName - the name of the property
   * @return The Class of the propery setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /*
   * Gets the type for a property getter
   *
   * @param propertyName - the name of the property
   * @return The Class of the propery getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /*
   * Gets an array of the readable properties for an object
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /*
   * Gets an array of the writeable properties for an object
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writeablePropertyNames;
  }

  /*
   * Check to see if a class has a writeable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writeable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /*
   * Check to see if a class has a readable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
