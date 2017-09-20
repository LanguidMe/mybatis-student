/*
 *    Copyright 2009-2013 the original author or authors.
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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * mapper.xml解析类
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

  private XPathParser parser;
  private MapperBuilderAssistant builderAssistant;
  private Map<String, XNode> sqlFragments;
  private String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }
  
  /**
   * 解析mapper.xml文件
   */
  public void parse() {
	  //先判断是否有这个mapper.xml
    if (!configuration.isResourceLoaded(resource)) {
    //解析mapper.xml
      configurationElement(parser.evalNode("/mapper"));
      //把资源位置添加到configuration中
      configuration.addLoadedResource(resource);
      //绑定mapper的命名空间
      bindMapperForNamespace();
    }
  //从configuration中移除未完成的ResultMaps  
    parsePendingResultMaps();
  //从configuration中移除未完成的ChacheRefs
    parsePendingChacheRefs();
  //从configuration中移除未完成的Statements
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }
  
  /**
   * 具体的解析mapper.xml方法
   * @param context
   */
  private void configurationElement(XNode context) {
    try {
      String namespace = context.getStringAttribute("namespace");
      if (namespace.equals("")) {
    	  throw new BuilderException("Mapper's namespace cannot be empty");
      }
      //保存命名空间
      builderAssistant.setCurrentNamespace(namespace);
      //详情  http://blog.csdn.net/isea533/article/details/44566257  大神不推荐使用
      cacheRefElement(context.evalNode("cache-ref"));
      cacheElement(context.evalNode("cache"));
      //解析parameterMap节点 并添加到configuration中<parameterMap></parameterMap>
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      //解析resultMap  --<resultMap></resultMap>
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      //解析sql <sql></sql>节点
      sqlElement(context.evalNodes("/mapper/sql"));
      //解析select|insert|update|delete节点 <select></select>
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. Cause: " + e, e);
    }
  }

  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
    	//如果数据源不为空  则配置数据源
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    //委托给buildStatementFromContext解析
    buildStatementFromContext(list, null);
  }

  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
    	//根据XNode节点信息 构造XMLStatementBuilder
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
    	  //解析select|insert|update|delete的XNode信息并构建MappedStatement,添加到configuration中
    	  //MappedStatements的map中Map<id-nameSpace,MappedStatement>
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
    	  //如果缓存引用不存在 则将statementParser添加到
    	  //configuration的IncompleteStatement集合中，LinkedList<XMLStatementBuilder>
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingChacheRefs() {
	  Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
	  synchronized (incompleteCacheRefs) {
		  Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
		  while (iter.hasNext()) {
			  try {
				  iter.next().resolveCacheRef();
				  iter.remove();
			  } catch (IncompleteElementException e) {
				  // Cache ref is still missing a resource...
			  }
		  }
	  }
  }

  private void parsePendingStatements() {
	  Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
	  synchronized (incompleteStatements) {
		  Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
		  while (iter.hasNext()) {
			  try {
				  iter.next().parseStatementNode();
				  iter.remove();
			  } catch (IncompleteElementException e) {
				  // Statement is still missing a resource...
			  }
		  }
	  }
  }

  private void cacheRefElement(XNode context) {
    if (context != null) {
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
    	  cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
    	  configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  private void cacheElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type", "PERPETUAL");
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      String eviction = context.getStringAttribute("eviction", "LRU");
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      Long flushInterval = context.getLongAttribute("flushInterval");
      Integer size = context.getIntAttribute("size");
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      Properties props = context.getChildrenAsProperties();
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, props);
    }
  }
  
  /**
   * 解析parameterMap节点 并添加到configuration中
   * @param list
   * @throws Exception
   */
  private void parameterMapElement(List<XNode> list) throws Exception {
    for (XNode parameterMapNode : list) {
    	//parameterMap的ID
      String id = parameterMapNode.getStringAttribute("id");
      //parameterMap的类型字符串
      String type = parameterMapNode.getStringAttribute("type");
      //获取parameterMap的类型
      Class<?> parameterClass = resolveClass(type);
      //获取所有的parameter节点
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
      //根据parameter节点 去创建parameterMappings
      for (XNode parameterNode : parameterNodes) {
    	//获取parameter的property，javaType，jdbcType，resultMap，mode，typeHandler，numericScale属性  
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        //获取mode的parameterMode类型
        ParameterMode modeEnum = resolveParameterMode(mode);
        //获取java类型
        Class<?> javaTypeClass = resolveClass(javaType);
        //获取jdbcType类型
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        //构建ParameterMapping
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) throws Exception {
    for (XNode resultMapNode : list) {
      try {
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.<ResultMapping> emptyList());
  }

  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
  //获取resultMap的id，type，extends，autoMapping 
    String id = resultMapNode.getStringAttribute("id",
        resultMapNode.getValueBasedIdentifier());
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    String extend = resultMapNode.getStringAttribute("extends");
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    //获取类型class
    Class<?> typeClass = resolveClass(type);
    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
    resultMappings.addAll(additionalResultMappings);
    //resultMap节点的所有result子节点，根据result节点信息，构造ResultMapping
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {
      if ("constructor".equals(resultChild.getName())) {//如果是构造函数
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {//获取对应的子类实体
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {//result子节点的处理，如果为id子节点
        ArrayList<ResultFlag> flags = new ArrayList<ResultFlag>();
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        //添加到resultMappings中
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    //把result的相关信息封装到ResultMapResolver中并返回resultMap
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
    	//如果有IncompleteElementException，则将resultMapResolver添加的IncompleteResultMap结合中
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
      ArrayList<ResultFlag> flags = new ArrayList<ResultFlag>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);
      }
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<String, String>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) {
    	//如果有数据源 则配置数据源
      sqlElement(list, configuration.getDatabaseId());
    }
    //委托给sqlElement
    sqlElement(list, null);
  }
  
  /**
   * sql节点处理
   * @param list
   * @param requiredDatabaseId
   * @throws Exception
   */
  private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
    for (XNode context : list) {
    	//获取sql节点的databaseId和id属性
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId))
    	  //将sql节点信息放入sqlFragments-map中  方便后面的select|insert等使用
    	  sqlFragments.put(id, context);
    }
  }
  
  /**
   * 判断sql的数据源与requiredDatabaseId是否匹配
   * @param id
   * @param databaseId
   * @param requiredDatabaseId
   * @return
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      if (databaseId != null) {
        return false;
      }
      // skip this fragment if there is a previous one with a not null databaseId
      //如果databaseId与requiredDatabaseId相等且不为空  就判断configuration中sqlFragments集合中是否有id对应的sqlFragments信息
      if (this.sqlFragments.containsKey(id)) {
        XNode context = this.sqlFragments.get(id);
        //如果不存在则返回
        if (context.getStringAttribute("databaseId") != null) {
          return false;
        }
      }
    }
    return true;
  }
  
  /**
   * 根据xml 和返回值类型 生成ResultMapping
   * @param context
   * @param resultType
   * @param flags
   * @return
   * @throws Exception
   */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, ArrayList<ResultFlag> flags) throws Exception {
    String property = context.getStringAttribute("property");
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String nestedSelect = context.getStringAttribute("select");
    String nestedResultMap = context.getStringAttribute("resultMap",
        processNestedResultMappings(context, Collections.<ResultMapping> emptyList()));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resulSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resulSet, foreignColumn, lazy);
  }
  
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      if (context.getStringAttribute("select") == null) {
        ResultMap resultMap = resultMapElement(context, resultMappings);
        return resultMap.getId();
      }
    }
    return null;
  }

  private void bindMapperForNamespace() {
	  //获取mapper.xml的命名空间
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
    	  //加载命名空间的class
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //ignore, bound type is not required
      }
      if (boundType != null) {
    	  //判断此类型的命名空间mapper是否存在
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
        	//configuration,将资源信息，添加的loadedResources中Set<resource:nameSpace>
          configuration.addLoadedResource("namespace:" + namespace);
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
