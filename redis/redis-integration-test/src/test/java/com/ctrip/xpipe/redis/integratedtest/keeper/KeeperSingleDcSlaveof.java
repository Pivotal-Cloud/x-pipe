package com.ctrip.xpipe.redis.integratedtest.keeper;

import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.ctrip.xpipe.api.server.PARTIAL_STATE;
import com.ctrip.xpipe.redis.core.meta.KeeperState;
import com.ctrip.xpipe.redis.keeper.RedisKeeperServer;
import com.ctrip.xpipe.redis.keeper.RedisSlave;
import com.ctrip.xpipe.redis.keeper.config.KeeperConfig;
import com.ctrip.xpipe.redis.keeper.config.TestKeeperConfig;
import com.ctrip.xpipe.redis.meta.server.job.SlaveofJob;
import com.ctrip.xpipe.redis.meta.server.job.XSlaveofJob;

/**
 * @author wenchao.meng
 *
 *         Aug 17, 2016
 */
public class KeeperSingleDcSlaveof extends AbstractKeeperIntegratedSingleDc {


	@Test
	public void testXSlaveof() throws Exception {

		testMakeRedisSlave(true);
	}
	
	@Override
	protected KeeperConfig getKeeperConfig() {
		
		TestKeeperConfig testKeeperConfig = (TestKeeperConfig) super.getKeeperConfig();
		testKeeperConfig.setReplicationStoreCommandFileSize(1 << 11);
		testKeeperConfig.setReplicationStoreCommandFileNumToKeep(1 << 20);
		return testKeeperConfig;
	}



	@Test
	public void testSlaveof() throws Exception {
		testMakeRedisSlave(false);
	}
	
	@Test
	public void testSlaveofBackup() throws Exception{
		
		sendMessageToMasterAndTestSlaveRedis();

		//test backupKeeper partial sync
		RedisKeeperServer backupKeeperServer = getRedisKeeperServer(backupKeeper);
		Set<RedisSlave> currentSlaves = backupKeeperServer.slaves();
		Assert.assertEquals(0, currentSlaves.size());

		logger.info(remarkableMessage("make slave slaves slaveof backup keeper"));
		new XSlaveofJob(slaves, backupKeeper.getIp(), backupKeeper.getPort(), getXpipeNettyClientKeyedObjectPool(), scheduled).execute();
		
		logger.info(remarkableMessage("make backup keeper active"));
		//make backup active
		setKeeperState(backupKeeper, KeeperState.ACTIVE, redisMaster.getIp(), redisMaster.getPort());
		
		sleep(1000);
		
		currentSlaves = backupKeeperServer.slaves();
		Assert.assertEquals(slaves.size(), currentSlaves.size());
		for(RedisSlave redisSlave : currentSlaves){
			Assert.assertEquals(PARTIAL_STATE.PARTIAL, redisSlave.partialState());
		}

	}

	private void testMakeRedisSlave(boolean xslaveof) throws Exception {
		
		sendMessageToMasterAndTestSlaveRedis();

		RedisKeeperServer backupKeeperServer = getRedisKeeperServer(backupKeeper);

		setKeeperState(backupKeeper, KeeperState.ACTIVE, redisMaster.getIp(), redisMaster.getPort(), false);

		setKeeperState(activeKeeper, KeeperState.BACKUP, backupKeeper.getIp(), backupKeeper.getPort(), false);

		if (xslaveof) {
			new XSlaveofJob(slaves, backupKeeper.getIp(), backupKeeper.getPort(), getXpipeNettyClientKeyedObjectPool(), scheduled).execute().sync();
		} else {
			new SlaveofJob(slaves, backupKeeper.getIp(), backupKeeper.getPort(), getXpipeNettyClientKeyedObjectPool(), scheduled).execute().sync();
		}

		sleep(2000);
		Set<RedisSlave> slaves = backupKeeperServer.slaves();
		Assert.assertEquals(3, slaves.size());
		for (RedisSlave redisSlave : slaves) {

			PARTIAL_STATE dest = PARTIAL_STATE.PARTIAL;
			if (redisSlave.getSlaveListeningPort() == activeKeeper.getPort()) {
				logger.info("[testXSlaveof][role keeper]{}, {}", redisSlave, redisSlave.partialState());
			} else {
				logger.info("[testXSlaveof][role redis]{}, {}", redisSlave, redisSlave.partialState());
				if (!xslaveof) {
					dest = PARTIAL_STATE.FULL;
				}
			}
			Assert.assertEquals(dest, redisSlave.partialState());
		}

		sendMessageToMasterAndTestSlaveRedis();
	}

	@Override
	protected boolean deleteTestDirAfterTest() {
		return false;
	}

}
