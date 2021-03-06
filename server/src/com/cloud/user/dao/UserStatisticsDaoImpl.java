/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package com.cloud.user.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.user.UserStatisticsVO;
import com.cloud.utils.DateUtil;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;

@Local(value={UserStatisticsDao.class})
public class UserStatisticsDaoImpl extends GenericDaoBase<UserStatisticsVO, Long> implements UserStatisticsDao {
    private static final Logger s_logger = Logger.getLogger(UserStatisticsDaoImpl.class);
    private static final String ACTIVE_AND_RECENTLY_DELETED_SEARCH = "SELECT us.id, us.data_center_id, us.account_id, us.public_ip_address, us.device_id, us.device_type, us.network_id, us.agg_bytes_received, us.agg_bytes_sent " +
                                                                     "FROM user_statistics us, account a " +
                                                                     "WHERE us.account_id = a.id AND (a.removed IS NULL OR a.removed >= ?) " +
                                                                     "ORDER BY us.id";
    private static final String UPDATE_AGG_STATS = "UPDATE user_statistics set agg_bytes_received = net_bytes_received + current_bytes_received , agg_bytes_sent = net_bytes_sent + current_bytes_sent";
    private final SearchBuilder<UserStatisticsVO> AllFieldsSearch;
    private final SearchBuilder<UserStatisticsVO> AccountSearch;
    
    public UserStatisticsDaoImpl() {
    	AccountSearch = createSearchBuilder();
    	AccountSearch.and("account", AccountSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
    	AccountSearch.done();

    	AllFieldsSearch = createSearchBuilder();
        AllFieldsSearch.and("account", AllFieldsSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
        AllFieldsSearch.and("dc", AllFieldsSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        AllFieldsSearch.and("network", AllFieldsSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        AllFieldsSearch.and("ip", AllFieldsSearch.entity().getPublicIpAddress(), SearchCriteria.Op.EQ);
        AllFieldsSearch.and("device", AllFieldsSearch.entity().getDeviceId(), SearchCriteria.Op.EQ);
        AllFieldsSearch.and("deviceType", AllFieldsSearch.entity().getDeviceType(), SearchCriteria.Op.EQ);        
        AllFieldsSearch.done();
    }
    
    @Override
    public UserStatisticsVO findBy(long accountId, long dcId, long networkId, String publicIp, Long deviceId, String deviceType) {
        SearchCriteria<UserStatisticsVO> sc = AllFieldsSearch.create();
        sc.setParameters("account", accountId);
        sc.setParameters("dc", dcId);
        sc.setParameters("network", networkId);
        sc.setParameters("ip", publicIp);
        sc.setParameters("device", deviceId);
        sc.setParameters("deviceType", deviceType);        
        return findOneBy(sc);
    }

    @Override
    public UserStatisticsVO lock(long accountId, long dcId, long networkId, String publicIp, Long deviceId, String deviceType) {
        SearchCriteria<UserStatisticsVO> sc = AllFieldsSearch.create();
        sc.setParameters("account", accountId);
        sc.setParameters("dc", dcId);
        sc.setParameters("network", networkId);
        sc.setParameters("ip", publicIp);
        sc.setParameters("device", deviceId);
        sc.setParameters("deviceType", deviceType);        
        return lockOneRandomRow(sc, true);
    }

    @Override
    public List<UserStatisticsVO> listBy(long accountId) {
        SearchCriteria<UserStatisticsVO> sc = AccountSearch.create();
        sc.setParameters("account", accountId);
        return search(sc, null);
    }

    @Override
    public List<UserStatisticsVO> listActiveAndRecentlyDeleted(Date minRemovedDate, int startIndex, int limit) {
        List<UserStatisticsVO> userStats = new ArrayList<UserStatisticsVO>();
        if (minRemovedDate == null) return userStats;

        Transaction txn = Transaction.currentTxn();
        try {
            String sql = ACTIVE_AND_RECENTLY_DELETED_SEARCH + " LIMIT " + startIndex + "," + limit;
            PreparedStatement pstmt = null;
            pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setString(1, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), minRemovedDate));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                userStats.add(toEntityBean(rs, false));
            }
        } catch (Exception ex) {
            s_logger.error("error saving user stats to cloud_usage db", ex);
        }
        return userStats;
    }
    
    @Override
    public boolean updateAggStats(){
    	Transaction txn = Transaction.currentTxn();
        try {
            String sql = UPDATE_AGG_STATS;
            PreparedStatement pstmt = null;
            pstmt = txn.prepareAutoCloseStatement(sql);
            return pstmt.executeUpdate() > 0;
        } catch (Exception ex) {
            s_logger.error("error updating agg user stats", ex);
        }
        return false;
    }
}
