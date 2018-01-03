package org.kairosdb.datastore.cassandra;

import com.codahale.metrics.*;
import com.datastax.driver.core.*;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.ExponentialReconnectionPolicy;
import com.datastax.driver.core.policies.LoadBalancingPolicy;
import com.datastax.driver.core.policies.RoundRobinPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.kairosdb.core.DataPointSet;
import org.kairosdb.core.datapoints.DoubleDataPointFactory;
import org.kairosdb.core.datapoints.DoubleDataPointFactoryImpl;
import org.kairosdb.core.datapoints.LongDataPointFactory;
import org.kairosdb.core.datapoints.LongDataPointFactoryImpl;
import org.kairosdb.core.reporting.KairosMetricReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 Created by bhawkins on 3/4/15.
 */
public class CassandraClientImpl implements CassandraClient, KairosMetricReporter
{
	public static final Logger logger = LoggerFactory.getLogger(CassandraClientImpl.class);

	private final Cluster m_cluster;
	private String m_keyspace;
	private LoadBalancingPolicy m_loadBalancingPolicy;

	@Inject
	@Named("HOSTNAME")
	private String m_hostName = "localhost";

	@Inject
	private LongDataPointFactory m_longDataPointFactory = new LongDataPointFactoryImpl();

	@Inject
	private DoubleDataPointFactory m_doubleDataPointFactory = new DoubleDataPointFactoryImpl();

	@Inject(optional=true)
	private AuthProvider m_authProvider = null;

	private final String m_clusterName;

	private final ClusterConfiguration m_clusterConfiguration;

	@Inject
	public CassandraClientImpl(ClusterConfiguration configuration)
	{
		m_clusterConfiguration = configuration;
		m_clusterName = configuration.getClusterName();
		//Passing shuffleReplicas = false so we can properly batch data to
		//instances.
		// When connecting to Cassandra notes in different datacenters, the local datacenter should be provided.
		// Not doing this will select the datacenter from the first connected Cassandra node, which is not guaranteed to be the correct one.
		m_loadBalancingPolicy = new TokenAwarePolicy((configuration.getLocalDCName() == null) ? new RoundRobinPolicy() : DCAwareRoundRobinPolicy.builder().withLocalDc(configuration.getLocalDCName()).build(), false);
		final Cluster.Builder builder = new Cluster.Builder()
				//.withProtocolVersion(ProtocolVersion.V3)
				.withPoolingOptions(new PoolingOptions().setConnectionsPerHost(HostDistance.LOCAL,
						configuration.getConnectionsLocalCore(), configuration.getConnectionsLocalMax())
						.setConnectionsPerHost(HostDistance.REMOTE,
						configuration.getConnectionsRemoteCore(), configuration.getConnectionsRemoteMax())
					.setMaxRequestsPerConnection(HostDistance.LOCAL, configuration.getRequestsPerConnectionLocal())
					.setMaxRequestsPerConnection(HostDistance.REMOTE, configuration.getRequestsPerConnectionRemote())
					.setMaxQueueSize(configuration.getMaxQueueSize()))
				.withReconnectionPolicy(new ExponentialReconnectionPolicy(100, 5 * 1000))
				.withLoadBalancingPolicy(m_loadBalancingPolicy)
				.withCompression(ProtocolOptions.Compression.LZ4)
				.withoutJMXReporting()
				.withQueryOptions(new QueryOptions().setConsistencyLevel(configuration.getReadConsistencyLevel()))
				.withTimestampGenerator(new TimestampGenerator() //todo need to remove this and put it only on the datapoints call
				{
					@Override
					public long next()
					{
						return System.currentTimeMillis();
					}
				});

		if (m_authProvider != null)
		{
			builder.withAuthProvider(m_authProvider);
		}
		else if (configuration.getAuthUser() != null && configuration.getAuthPassword() != null)
		{
			builder.withCredentials(configuration.getAuthUser(),
					configuration.getAuthPassword());
		}


		for (String node : configuration.getHostList())
		{
			logger.info("Connecting to "+node);
			builder.addContactPoint(node);
		}

		if (configuration.isUseSsl())
			builder.withSSL();

		m_cluster = builder.build();
		m_keyspace = configuration.getKeyspace();
	}

	public LoadBalancingPolicy getLoadBalancingPolicy()
	{
		return m_loadBalancingPolicy;
	}

	public ClusterConfiguration getClusterConfiguration()
	{
		return m_clusterConfiguration;
	}

	@Override
	public Session getKeyspaceSession()
	{
		return m_cluster.connect(m_keyspace);
	}

	@Override
	public Session getSession()
	{
		return m_cluster.connect();
	}

	@Override
	public String getKeyspace()
	{
		return m_keyspace;
	}

	@Override
	public void close()
	{
		m_cluster.close();
	}


	private DataPointSet newDataPointSet(String metricPrefix, String metricSuffix,
			long now, long value)
	{
		DataPointSet dps = new DataPointSet(new StringBuilder(metricPrefix).append(".").append(metricSuffix).toString());
		dps.addTag("host", m_hostName);
		dps.addTag("cluster", m_clusterName);
		dps.addDataPoint(m_longDataPointFactory.createDataPoint(now, value));

		return dps;
	}

	private DataPointSet newDataPointSet(String metricPrefix, String metricSuffix,
			long now, double value)
	{
		DataPointSet dps = new DataPointSet(new StringBuilder(metricPrefix).append(".").append(metricSuffix).toString());
		dps.addTag("host", m_hostName);
		dps.addTag("cluster", m_clusterName);
		dps.addDataPoint(m_doubleDataPointFactory.createDataPoint(now, value));

		return dps;
	}

	@Override
	public List<DataPointSet> getMetrics(long now)
	{
		String prefix = "kairosdb.datastore.cassandra.client";
		List<DataPointSet> ret = new ArrayList<>();
		Metrics metrics = m_cluster.getMetrics();

		ret.add(newDataPointSet(prefix, "blocking_executor_queue_depth", now,
				metrics.getBlockingExecutorQueueDepth().getValue()));

		ret.add(newDataPointSet(prefix, "connected_to_hosts", now,
				metrics.getConnectedToHosts().getValue()));

		ret.add(newDataPointSet(prefix, "executor_queue_depth", now,
				metrics.getExecutorQueueDepth().getValue()));

		ret.add(newDataPointSet(prefix, "known_hosts", now,
				metrics.getKnownHosts().getValue()));

		ret.add(newDataPointSet(prefix, "open_connections", now,
				metrics.getOpenConnections().getValue()));

		ret.add(newDataPointSet(prefix, "reconnection_scheduler_queue_size", now,
				metrics.getReconnectionSchedulerQueueSize().getValue()));

		ret.add(newDataPointSet(prefix, "task_scheduler_queue_size", now,
				metrics.getTaskSchedulerQueueSize().getValue()));

		ret.add(newDataPointSet(prefix, "trashed_connections", now,
				metrics.getTrashedConnections().getValue()));

		Snapshot snapshot = metrics.getRequestsTimer().getSnapshot();
		prefix = prefix + ".requests_timer";
		ret.add(newDataPointSet(prefix, "max", now,
				snapshot.getMax()));

		ret.add(newDataPointSet(prefix, "min", now,
				snapshot.getMin()));

		ret.add(newDataPointSet(prefix, "avg", now,
				snapshot.getMean()));

		ret.add(newDataPointSet(prefix, "count", now,
				snapshot.size()));

		return ret;
	}
}