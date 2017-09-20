package tk.mybatis.simple;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.binding.MapperProxyFactory;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.jdbc.JdbcTransaction;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.junit.Test;

public class SimpleTest {
	
	@Test
	public void test() throws IOException,SQLException{
		//使用log4j记录日志
		LogFactory.useLog4JLogging();
		//创建配置对象
		final Configuration config = new Configuration();
		config.setCacheEnabled(true);
		config.setLazyLoadingEnabled(false);
		config.setAggressiveLazyLoading(true);
		//第二部分添加拦截器
		SimpleInterceptor interceptor1 = new SimpleInterceptor("拦截器1");
		SimpleInterceptor interceptor2 = new SimpleInterceptor("拦截器2");
		config.addInterceptor(interceptor1);
		config.addInterceptor(interceptor2);
		//第三部分创建数据源和JDBC事务
		UnpooledDataSource dataSource = new UnpooledDataSource();
		dataSource.setDriver("com.mysql.jdbc.Driver");
		dataSource.setUrl("jdbc:mysql://localhost:3306/mybatis");
		dataSource.setUsername("root");
		dataSource.setPassword("123456");
		
		Transaction transaction = new JdbcTransaction(dataSource, null, false);
		//第四部分 创建Executor
		final Executor executor = config.newExecutor(transaction);
		//第五部分 创建SqlSource对象
		StaticSqlSource sqlSource = new StaticSqlSource(config, "SELECT * FROM country WHERE id =?");
		
		//第六部分 创建参数映射配置
		TypeHandlerRegistry registry = new TypeHandlerRegistry();
		List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
		parameterMappings.add(new ParameterMapping.Builder(config, "id", registry.getTypeHandler(Long.class)).build());
		//class是实体对象的class
		ParameterMap.Builder paramBuilder = new ParameterMap.Builder(config, "defaultParameterMap",Country.class, parameterMappings);
		
		//第七部分 创建结果映射
//		List<ResultMapping> mappings = new ArrayList<ResultMapping>();
//		mappings.add(new ResultMapping.Builder(config, "id", "id", Long.class).build());
//		mappings.add(new ResultMapping.Builder(config, "countryname", "countryname", String.class).build());
//		mappings.add(new ResultMapping.Builder(config, "countrycode", "countrycode", registry.getTypeHandler(String.class)).build());
		//one
		//ResultMap resultMap = new ResultMap.Builder(config, "defaultResultMap", this.getClass(), mappings).build();
		//two
		ResultMap resultMap = new ResultMap.Builder(config, "defaultResultMap", Country.class, new ArrayList<ResultMapping>()).build();
		//第八部分 创建缓存对象
		//final Cache countryCache = new SynchronizedCache(new SerializedCache(new LoggingCache(new LruCache(new PerpetualCache("country_cache")))));
		//第九部分 创建MappedStatement对象
		MappedStatement.Builder msBuilder = new MappedStatement.Builder(config, "tk.mybatis.simple.SimpleMapper.selectCountry", sqlSource, SqlCommandType.SELECT);
		msBuilder.parameterMap(paramBuilder.build());
		List<ResultMap> resultMaps = new ArrayList<ResultMap>();
		resultMaps.add(resultMap);
		//设置返回值
		//msBuilder.resultMaps(resultMaps);
		//设置缓存
		//msBuilder.cache(countryCache);
		//创建ms
		MappedStatement mappedStatement = msBuilder.build();
		List<Country> countrys = executor.query(mappedStatement, 3L, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
		System.out.println(countrys.get(0).getCountryname());
		config.addMappedStatement(mappedStatement);
		SqlSession sqlSession = new DefaultSqlSession(config, executor, false);
		Country Sqlsessioncountry = sqlSession.selectOne("selectCountry", 2L);
		System.out.println(Sqlsessioncountry.getCountryname());
		MapperProxyFactory<SimpleMapper> mapperProxyFactory = new MapperProxyFactory<SimpleMapper>(SimpleMapper.class);
		SimpleMapper simpleMapper = mapperProxyFactory.newInstance(sqlSession);
		Country country = simpleMapper.selectCountry(1L);
		System.out.println(country.getCountrycode());
	}
}
