/*
 *    Copyright 2009-2014 the original author or authors.
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
package org.apache.ibatis.builder.annotation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.CacheNamespaceRef;
import org.apache.ibatis.annotations.Case;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.TypeDiscriminator;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.FetchType;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * 用于Mapper注解的解析
 */
public class MapperAnnotationBuilder {
	
	/**
	 * 放置Select.class之类的四个注解
	 */
  private final Set<Class<? extends Annotation>> sqlAnnotationTypes = new HashSet<Class<? extends Annotation>>();
  /**
   * 放置SelectProvider.class之类的四个注解
   */
  private final Set<Class<? extends Annotation>> sqlProviderAnnotationTypes = new HashSet<Class<? extends Annotation>>();

  private Configuration configuration;
  private MapperBuilderAssistant assistant;
  private Class<?> type;

  public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
    String resource = type.getName().replace('.', '/') + ".java (best guess)";
    this.assistant = new MapperBuilderAssistant(configuration, resource);
    this.configuration = configuration;
    this.type = type;

    sqlAnnotationTypes.add(Select.class);
    sqlAnnotationTypes.add(Insert.class);
    sqlAnnotationTypes.add(Update.class);
    sqlAnnotationTypes.add(Delete.class);

    sqlProviderAnnotationTypes.add(SelectProvider.class);
    sqlProviderAnnotationTypes.add(InsertProvider.class);
    sqlProviderAnnotationTypes.add(UpdateProvider.class);
    sqlProviderAnnotationTypes.add(DeleteProvider.class);
  }
  
  /**
   * 解析mapper方法
   */
  public void parse() {
    String resource = type.toString();//获取类型的命名空间
    if (!configuration.isResourceLoaded(resource)) {//判断是否有这个mapper的资源路径
      loadXmlResource();//加载xml资源
      configuration.addLoadedResource(resource);//添加
      assistant.setCurrentNamespace(type.getName());//设置命名空间
      //解析缓存 两个配置不能同时使用
      parseCache();
      parseCacheRef();
      Method[] methods = type.getMethods();//获取该类的所有方法
      for (Method method : methods) {
        try {
          if (!method.isBridge()) { // issue #237 如果这个方法不是桥接的方法
            parseStatement(method);//解析这个方法
          }
        } catch (IncompleteElementException e) {//失败的话 把这个方法添加到 configuration的集合中去
          configuration.addIncompleteMethod(new MethodResolver(this, method));
        }
      }
    }
    //开始解析 之前失败的Method方法
    parsePendingMethods();
  }
  
  /**
   * 解析上面方法失败的Method
   */
  private void parsePendingMethods() {
    Collection<MethodResolver> incompleteMethods = configuration.getIncompleteMethods();
    synchronized (incompleteMethods) {
      Iterator<MethodResolver> iter = incompleteMethods.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // This method is still missing a resource
        }
      }
    }
  }
  
  /**
   * 加载xml资源文件
   */
  private void loadXmlResource() {
    // Spring may not know the real resource name so we check a flag
    // to prevent loading again a resource twice
    // this flag is set at XMLMapperBuilder#bindMapperForNamespace
    if (!configuration.isResourceLoaded("namespace:" + type.getName())) {
      String xmlResource = type.getName().replace('.', '/') + ".xml";
      InputStream inputStream = null;
      try {
        inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);
      } catch (IOException e) {
        // ignore, resource is not required
      }
      if (inputStream != null) {//如果不为空 则交给XMLMapperBuilder去解析
        XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource, configuration.getSqlFragments(), type.getName());
        xmlParser.parse();
      }
    }
  }
  
  /**
   * 解析缓存注解CacheNamespace.class
   */
  private void parseCache() {
    CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
    if (cacheDomain != null) {
    	//生成Cache并添加到configuration中
      assistant.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), cacheDomain.flushInterval(), cacheDomain.size(), cacheDomain.readWrite(), null);
    }
  }
  
  /**
   * 去解析缓存注解CacheNamespaceRef.class
   */
  private void parseCacheRef() {
    CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);
    if (cacheDomainRef != null) {
      assistant.useCacheRef(cacheDomainRef.value().getName());
    }
  }
  
  /**
   * 解析ResultMap
   * @param method
   * @return
   */
  private String parseResultMap(Method method) {
    Class<?> returnType = getReturnType(method);
    ConstructorArgs args = method.getAnnotation(ConstructorArgs.class);
    Results results = method.getAnnotation(Results.class);
    TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
    //方法返回结果唯一id
    String resultMapId = generateResultMapName(method);
    applyResultMap(resultMapId, returnType, argsIf(args), resultsIf(results), typeDiscriminator);
    return resultMapId;
  }
  
  /**
   * 生成ResultMap 名称  也是唯一的
   * @param method
   * @return
   */
  private String generateResultMapName(Method method) {
    StringBuilder suffix = new StringBuilder();
    for (Class<?> c : method.getParameterTypes()) {
      suffix.append("-");
      suffix.append(c.getSimpleName());
    }
    if (suffix.length() < 1) {
      suffix.append("-void");
    }
    return type.getName() + "." + method.getName() + suffix;
  }
  
  /**
   * 生成ResultMap对象
   * @param resultMapId ResultMap唯一ID
   * @param returnType 方法的返回值类型
   * @param args 方法上的Arg注解数组
   * @param results 方法上Result注解数组
   * @param discriminator
   */
  private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args, Result[] results, TypeDiscriminator discriminator) {
    List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
    //根据args的属性和返回值类型  生成返回(resultMapping)参数  并添加到resultMappings中
    applyConstructorArgs(args, returnType, resultMappings);
    //根据Result注解数组  和返回值类型  生成返回(resultMapping)参数  并添加到resultMappings中
    applyResults(results, returnType, resultMappings);
    Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
    //构建resultMap
    assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null); // TODO add AutoMappingBehaviour
    createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
  }

  private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      for (Case c : discriminator.cases()) {
        String caseResultMapId = resultMapId + "-" + c.value();
        List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
        applyConstructorArgs(c.constructArgs(), resultType, resultMappings); // issue #136
        applyResults(c.results(), resultType, resultMappings);
        assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings, null); // TODO add AutoMappingBehaviour
      }
    }
  }

  private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      String column = discriminator.column();
      Class<?> javaType = discriminator.javaType() == void.class ? String.class : discriminator.javaType();
      JdbcType jdbcType = discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
      Class<? extends TypeHandler<?>> typeHandler = discriminator.typeHandler() == UnknownTypeHandler.class ? null : discriminator.typeHandler();
      Case[] cases = discriminator.cases();
      Map<String, String> discriminatorMap = new HashMap<String, String>();
      for (Case c : cases) {
        String value = c.value();
        String caseResultMapId = resultMapId + "-" + value;
        discriminatorMap.put(value, caseResultMapId);
      }
      return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler, discriminatorMap);
    }
    return null;
  }
  
  /**
   * 解析Medthod方法
   * @param method
   */
  void parseStatement(Method method) {
	 //获取method参数类型
    Class<?> parameterTypeClass = getParameterType(method);
    //获取method的LanguageDriver
    LanguageDriver languageDriver = getLanguageDriver(method);
    //构建sqlSource
    SqlSource sqlSource = getSqlSourceFromAnnotations(method, parameterTypeClass, languageDriver);
    if (sqlSource != null) {
    //获取Options注解
      Options options = method.getAnnotation(Options.class);
      //构造mappedStatementId 
      final String mappedStatementId = type.getName() + "." + method.getName();
      Integer fetchSize = null;
      Integer timeout = null;
      StatementType statementType = StatementType.PREPARED;
      ResultSetType resultSetType = ResultSetType.FORWARD_ONLY;
      //获取Method查询类型
      SqlCommandType sqlCommandType = getSqlCommandType(method);
      boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    //是否刷新缓存，如果不是SELECT，则刷新缓存，否，不刷新缓存
      boolean flushCache = !isSelect;
    //是否用缓存，SELECT则用缓存，否，不用缓存
      boolean useCache = isSelect;

      KeyGenerator keyGenerator;
      String keyProperty = "id";
      String keyColumn = null;
    //如果是SQL是INSERT类型,isUseGeneratedKeys属性获取对应的keyGenerator
      if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {
        // first check for SelectKey annotation - that overrides everything else
        SelectKey selectKey = method.getAnnotation(SelectKey.class);
        if (selectKey != null) {
          keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method), languageDriver);
          keyProperty = selectKey.keyProperty();
        } else {
          if (options == null) {
            keyGenerator = configuration.isUseGeneratedKeys() ? new Jdbc3KeyGenerator() : new NoKeyGenerator();
          } else {
            keyGenerator = options.useGeneratedKeys() ? new Jdbc3KeyGenerator() : new NoKeyGenerator();
            keyProperty = options.keyProperty();
            keyColumn = options.keyColumn();
          }
        }
      } else {
        keyGenerator = new NoKeyGenerator();
      }

      if (options != null) {
    	//根据Options注解信息，获取flushCache，useCache，fetchSize，timeout，statementType，resultSetType等信息
        flushCache = options.flushCache();
        useCache = options.useCache();
        fetchSize = options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options.fetchSize() : null; //issue #348
        timeout = options.timeout() > -1 ? options.timeout() : null;
        statementType = options.statementType();
        resultSetType = options.resultSetType();
      }

      String resultMapId = null;
    //获取ResultMap注解信息
      ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
      if (resultMapAnnotation != null) {
        String[] resultMaps = resultMapAnnotation.value();
        StringBuilder sb = new StringBuilder();
        for (String resultMap : resultMaps) {
          if (sb.length() > 0) sb.append(",");
          sb.append(resultMap);
        }
        resultMapId = sb.toString();
      } else if (isSelect) {
    	//解析method的resultMap
        resultMapId = parseResultMap(method);
      }
    //根据方法的注解信息，构建MappedStatement  
      //并添加到configuration的mappedStatements的Map中，Map<id-nameSpace,MappedStatement>
      assistant.addMappedStatement(
          mappedStatementId,
          sqlSource,
          statementType,
          sqlCommandType,
          fetchSize,
          timeout,
          null,                             // ParameterMapID
          parameterTypeClass,
          resultMapId,    // ResultMapID
          getReturnType(method),
          resultSetType,
          flushCache,
          useCache,
          false, // TODO issue #577
          keyGenerator,
          keyProperty,
          keyColumn,
          null,
          languageDriver,
          null);
    }
  }
  
  /**
   * 获取解析SQL里面节点的解析器  一般会使用默认的XMLLanguageDriver.class
   * @param method
   * @return
   */
  private LanguageDriver getLanguageDriver(Method method) {
    Lang lang = method.getAnnotation(Lang.class);
    Class<?> langClass = null;
    if (lang != null) {
      langClass = lang.value();
    }
    return assistant.getLanguageDriver(langClass);
  }
  
  /**
   * 获取参数类型
   * @param method
   * @return
   */
  private Class<?> getParameterType(Method method) {
    Class<?> parameterType = null;
    Class<?>[] parameterTypes = method.getParameterTypes();
    for (int i = 0; i < parameterTypes.length; i++) {
      if (!RowBounds.class.isAssignableFrom(parameterTypes[i]) && !ResultHandler.class.isAssignableFrom(parameterTypes[i])) {
        if (parameterType == null) {
          parameterType = parameterTypes[i];
        } else {
          parameterType = ParamMap.class; // issue #135
        }
      }
    }
    return parameterType;
  }
  
  /**
   * 获取返回值类型
   * @param method
   * @return
   */
  private Class<?> getReturnType(Method method) {
    Class<?> returnType = method.getReturnType();
    if (void.class.equals(returnType)) { // issue #508
      ResultType rt = method.getAnnotation(ResultType.class);
      if (rt != null) {
        returnType = rt.value();
      } 
    } else if (Collection.class.isAssignableFrom(returnType)) {
      Type returnTypeParameter = method.getGenericReturnType();
      if (returnTypeParameter instanceof ParameterizedType) {
        Type[] actualTypeArguments = ((ParameterizedType) returnTypeParameter).getActualTypeArguments();
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          returnTypeParameter = actualTypeArguments[0];
          if (returnTypeParameter instanceof Class) {
            returnType = (Class<?>) returnTypeParameter;
          } else if (returnTypeParameter instanceof ParameterizedType) { // (issue #443) actual type can be a also a parametrized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          } else if (returnTypeParameter instanceof GenericArrayType) {
            Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter).getGenericComponentType();
            returnType = Array.newInstance(componentType, 0).getClass(); // (issue #525) support List<byte[]>
          }
        }
      }
    } else if (method.isAnnotationPresent(MapKey.class) && Map.class.isAssignableFrom(returnType)) {
      // (issue 504) Do not look into Maps if there is not MapKey annotation
      Type returnTypeParameter = method.getGenericReturnType();
      if (returnTypeParameter instanceof ParameterizedType) {
        Type[] actualTypeArguments = ((ParameterizedType) returnTypeParameter).getActualTypeArguments();
        if (actualTypeArguments != null && actualTypeArguments.length == 2) {
          returnTypeParameter = actualTypeArguments[1];
          if (returnTypeParameter instanceof Class) {
            returnType = (Class<?>) returnTypeParameter;
          } else if (returnTypeParameter instanceof ParameterizedType) { // (issue 443) actual type can be a also a parametrized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          }
        }
      }
    }

    return returnType;
  }
  
  /**
   * 根据参数 去构建SqlSource对象
   * @param method 方法对象
   * @param parameterType 参数类型
   * @param languageDriver 解析SQL的解析器对象
   * @return
   */
  private SqlSource getSqlSourceFromAnnotations(Method method, Class<?> parameterType, LanguageDriver languageDriver) {
    try {
    	//这里看方法名称就能明白
      Class<? extends Annotation> sqlAnnotationType = getSqlAnnotationType(method);
      Class<? extends Annotation> sqlProviderAnnotationType = getSqlProviderAnnotationType(method);
      if (sqlAnnotationType != null) {
        if (sqlProviderAnnotationType != null) {
          throw new BindingException("You cannot supply both a static SQL and SqlProvider to method named " + method.getName());
        }
        Annotation sqlAnnotation = method.getAnnotation(sqlAnnotationType);
        //根据反射和动态代理 去获取到注解的Value值
        final String[] strings = (String[]) sqlAnnotation.getClass().getMethod("value").invoke(sqlAnnotation);
        return buildSqlSourceFromStrings(strings, parameterType, languageDriver);
      } else if (sqlProviderAnnotationType != null) {
        Annotation sqlProviderAnnotation = method.getAnnotation(sqlProviderAnnotationType);
        return new ProviderSqlSource(assistant.getConfiguration(), sqlProviderAnnotation);
      }
      return null;
    } catch (Exception e) {
      throw new BuilderException("Could not find value method on SQL annotation.  Cause: " + e, e);
    }
  }
  
  /**
   * 根据解析 创建sqlSource对象 根据strings数组信息
   * @param strings 注解上面value[]的值
   * @param parameterTypeClass 参数类型
   * @param languageDriver 解析SQL的解析器
   * @return
   */
  private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
    final StringBuilder sql = new StringBuilder();
    for (String fragment : strings) {
      sql.append(fragment);
      sql.append(" ");
    }
    return languageDriver.createSqlSource(configuration, sql.toString(), parameterTypeClass);
  }
/**
 * 获取Method的SQL类型
 * @param method
 * @return
 */
  private SqlCommandType getSqlCommandType(Method method) {
    Class<? extends Annotation> type = getSqlAnnotationType(method);

    if (type == null) {
      type = getSqlProviderAnnotationType(method);

      if (type == null) {
        return SqlCommandType.UNKNOWN;
      }

      if (type == SelectProvider.class) {
        type = Select.class;
      } else if (type == InsertProvider.class) {
        type = Insert.class;
      } else if (type == UpdateProvider.class) {
        type = Update.class;
      } else if (type == DeleteProvider.class) {
        type = Delete.class;
      }
    }

    return SqlCommandType.valueOf(type.getSimpleName().toUpperCase(Locale.ENGLISH));
  }

  private Class<? extends Annotation> getSqlAnnotationType(Method method) {
    return chooseAnnotationType(method, sqlAnnotationTypes);
  }

  private Class<? extends Annotation> getSqlProviderAnnotationType(Method method) {
    return chooseAnnotationType(method, sqlProviderAnnotationTypes);
  }

  private Class<? extends Annotation> chooseAnnotationType(Method method, Set<Class<? extends Annotation>> types) {
    for (Class<? extends Annotation> type : types) {
      Annotation annotation = method.getAnnotation(type);
      if (annotation != null) {
        return type;
      }
    }
    return null;
  }
  
  /**
   * 解析results注解  根据注解信息构造resultMapping  并添加到resultMappings
   * @param results
   * @param resultType
   * @param resultMappings
   */
  private void applyResults(Result[] results, Class<?> resultType, List<ResultMapping> resultMappings) {
    for (Result result : results) {
      ArrayList<ResultFlag> flags = new ArrayList<ResultFlag>();
      if (result.id()) flags.add(ResultFlag.ID);
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(result.property()),
          nullOrEmpty(result.column()),
          result.javaType() == void.class ? null : result.javaType(),
          result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(),
          hasNestedSelect(result) ? nestedSelectId(result) : null,
          null,
          null,
          null,
          result.typeHandler() == UnknownTypeHandler.class ? null : result.typeHandler(),
          flags,
          null,
          null,
          isLazy(result));
      resultMappings.add(resultMapping);
    }
  }
  
  /**
   * 获取嵌套查询的id
   * @param result
   * @return
   */
  private String nestedSelectId(Result result) {
    String nestedSelect = result.one().select();
    if (nestedSelect.length() < 1) {
      nestedSelect = result.many().select();
    }
    if (!nestedSelect.contains(".")) {
      nestedSelect = type.getName() + "." + nestedSelect;
    }
    return nestedSelect;
  }

  private boolean isLazy(Result result) {
    Boolean isLazy = configuration.isLazyLoadingEnabled();
    if (result.one().select().length() > 0 && FetchType.DEFAULT != result.one().fetchType()) {
      isLazy = (result.one().fetchType() == FetchType.LAZY);
    } else if (result.many().select().length() > 0 && FetchType.DEFAULT != result.many().fetchType()) {
      isLazy = (result.many().fetchType() == FetchType.LAZY);
    }
    return isLazy;
  }
  
  private boolean hasNestedSelect(Result result) {
    if (result.one().select().length() > 0 && result.many().select().length() > 0) {
      throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
    }
    return result.one().select().length() > 0 || result.many().select().length() > 0;  
  }

  /**
   * 根据args的属性和返回值  生成返回(resultMapping)参数  并添加到resultMappings中
   * @param args
   * @param resultType
   * @param resultMappings
   */
  private void applyConstructorArgs(Arg[] args, Class<?> resultType, List<ResultMapping> resultMappings) {
    for (Arg arg : args) {
      ArrayList<ResultFlag> flags = new ArrayList<ResultFlag>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if (arg.id()) flags.add(ResultFlag.ID);
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          null,
          nullOrEmpty(arg.column()),
          arg.javaType() == void.class ? null : arg.javaType(),
          arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(),
          nullOrEmpty(arg.select()),
          nullOrEmpty(arg.resultMap()),
          null,
          null,
          arg.typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler(),
          flags,
          null,
          null,
          false);
      resultMappings.add(resultMapping);
    }
  }

  private String nullOrEmpty(String value) {
    return value == null || value.trim().length() == 0 ? null : value;
  }

  private Result[] resultsIf(Results results) {
    return results == null ? new Result[0] : results.value();
  }

  private Arg[] argsIf(ConstructorArgs args) {
    return args == null ? new Arg[0] : args.value();
  }

  private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation, String baseStatementId, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
    String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    Class<?> resultTypeClass = selectKeyAnnotation.resultType();
    StatementType statementType = selectKeyAnnotation.statementType();
    String keyProperty = selectKeyAnnotation.keyProperty();
    String keyColumn = selectKeyAnnotation.keyColumn();
    boolean executeBefore = selectKeyAnnotation.before();

    // defaults
    boolean useCache = false;
    KeyGenerator keyGenerator = new NoKeyGenerator();
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    SqlSource sqlSource = buildSqlSourceFromStrings(selectKeyAnnotation.statement(), parameterTypeClass, languageDriver);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    assistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum,
        flushCache, useCache, false,
        keyGenerator, keyProperty, keyColumn, null, languageDriver, null);

    id = assistant.applyCurrentNamespace(id, false);

    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
    configuration.addKeyGenerator(id, answer);
    return answer;
  }

}
