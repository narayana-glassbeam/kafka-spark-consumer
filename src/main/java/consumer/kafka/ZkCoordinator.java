/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 *   This file is based on the source code of the Kafka spout of the Apache Storm project.
 *   (https://github.com/apache/storm/tree/master/external/storm-kafka)
 *   This file has been modified to work with Spark Streaming.
 */

package consumer.kafka;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.streaming.receiver.Receiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZkCoordinator implements PartitionCoordinator, Serializable {
	public static final Logger LOG = LoggerFactory
			.getLogger(ZkCoordinator.class);

	KafkaConfig _kafkaconfig;
	int _partitionOwner;
	Map<Partition, PartitionManager> _managers = new HashMap();
	List<PartitionManager> _cachedList;
	Long _lastRefreshTime = 0L;
	int _refreshFreqMs;
	DynamicPartitionConnections _connections;
	DynamicBrokersReader _reader;
	GlobalPartitionInformation _brokerInfo;
	KafkaConfig _config;
	Receiver _receiver;
	boolean _restart;

	public ZkCoordinator(DynamicPartitionConnections connections,
			KafkaConfig config, ZkState state, int partitionId,
			Receiver receiver, boolean restart) {
		_kafkaconfig = config;
		_connections = connections;
		_partitionOwner = partitionId;
		_refreshFreqMs = config._refreshFreqSecs * 1000;
		_reader = new DynamicBrokersReader(_kafkaconfig, state);
		_brokerInfo = _reader.getBrokerInfo();
		_config = config;
		_receiver = receiver;
		_restart = restart;

	}

	@Override
	public List<PartitionManager> getMyManagedPartitions() {
		if ((System.currentTimeMillis() - _lastRefreshTime) > _refreshFreqMs) {
			refresh();
			_lastRefreshTime = System.currentTimeMillis();
		}
		_restart = false;
		return _cachedList;
	}

	@Override
	public void refresh() {
		try {
			LOG.info("Refreshing partition manager connections");
			_brokerInfo = _reader.getBrokerInfo();
			Set<Partition> mine = new HashSet();
			for (Partition partition : _brokerInfo) {
				if (partition.partition == _partitionOwner) {
					mine.add(partition);
					LOG.info("Added partition index " + _partitionOwner
							+ " for coordinator");
				}
			}

			if (mine.size() == 0) {

				LOG.warn("Some issue getting Partition details.. Patrition Manager size Zero");
				_managers.clear();
				_cachedList.clear();
				return;
			} else {

				Set<Partition> curr = _managers.keySet();
				Set<Partition> newPartitions = new HashSet<Partition>(mine);
				newPartitions.removeAll(curr);

				Set<Partition> deletedPartitions = new HashSet<Partition>(curr);
				deletedPartitions.removeAll(mine);

				LOG.info("Deleted partition managers: "
						+ deletedPartitions.toString());

				for (Partition id : deletedPartitions) {
					PartitionManager man = _managers.remove(id);
					man.close();
				}
				LOG.info("New partition managers: " + newPartitions.toString());

				for (Partition id : newPartitions) {

					PartitionManager man = new PartitionManager(
							_connections,
							new ZkState((String) _config._stateConf
									.get(Config.ZOOKEEPER_CONSUMER_CONNECTION)),
							_kafkaconfig, id, _receiver, _restart);
					_managers.put(id, man);
				}

				_cachedList = new ArrayList<PartitionManager>(
						_managers.values());
				LOG.info("Finished refreshing");
			}

		} catch (Exception e) {
			throw new FailedFetchException(e);
		}
	}

	@Override
	public PartitionManager getManager(Partition partition) {
		return _managers.get(partition);
	}
}
