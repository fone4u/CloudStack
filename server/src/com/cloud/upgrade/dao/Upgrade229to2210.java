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
package com.cloud.upgrade.dao;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.apache.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

public class Upgrade229to2210 implements DbUpgrade {
    final static Logger s_logger = Logger.getLogger(Upgrade229to2210.class);

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[] { "2.2.9", "2.2.9"};
    }

    @Override
    public String getUpgradedVersion() {
        return "2.2.10";
    }

    @Override
    public boolean supportsRollingUpgrade() {
        return true;
    }

    @Override
    public File[] getPrepareScripts() {
        String script = Script.findScript("", "db/schema-229to2210.sql");
        if (script == null) {
            throw new CloudRuntimeException("Unable to find db/schema-229to2210.sql");
        }
        
        return new File[] { new File(script) };
    }

    @Override
    public void performDataMigration(Connection conn) {
        updateFirewallRules(conn);
    }

    @Override
    public File[] getCleanupScripts() {
        return null;
    }
    
    
    private void updateFirewallRules(Connection conn) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        long currentRuleId = 0;
        try {
            // Host and Primary storage capacity types
            pstmt = conn.prepareStatement("select id, ip_address_id, start_port, end_port, protocol, account_id, domain_id, network_id from firewall_rules");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                long id = rs.getLong(1);
                long ipId = rs.getLong(2);
                int startPort = rs.getInt(3);
                int endPort = rs.getInt(4);
                String protocol = rs.getString(5);
                long accountId = rs.getLong(6);
                long domainId = rs.getLong(7);
                long networkId = rs.getLong(8);
                currentRuleId = id;
                Long firewallRuleId = null;
                
                pstmt = conn.prepareStatement("INSERT INTO firewall_rules (ip_address_id, start_port, end_port, protocol, account_id, domain_id, network_id, purpose, state, xid, created, related) VALUES (?, ?, ?, ?, ?, ?, ?, 'Firewall', 'Active', ?, now(), ?)");
                
                pstmt.setLong(1, ipId);
                pstmt.setInt(2, startPort);
                pstmt.setInt(3, endPort);
                pstmt.setString(4, protocol);
                pstmt.setLong(5, accountId);
                pstmt.setLong(6, domainId);
                pstmt.setLong(7, networkId);
                pstmt.setString(8, UUID.randomUUID().toString());
                pstmt.setLong(9, id);
                
                s_logger.debug("Updating firewall rule with the statement " + pstmt);
                pstmt.executeUpdate();
                
                //get new FirewallRule update
                pstmt = conn.prepareStatement("SELECT id from firewall_rules where purpose='Firewall' and start_port=? and end_port=? and protocol=? and ip_address_id=? and network_id=?");
                pstmt.setInt(1, startPort);
                pstmt.setInt(2, endPort);
                pstmt.setString(3, protocol);
                pstmt.setLong(4, ipId);
                pstmt.setLong(5, networkId);
                
                ResultSet rs1 = pstmt.executeQuery();
                
                if (rs1.next()) {
                    firewallRuleId = rs1.getLong(1);
                } else {
                    throw new CloudRuntimeException("Unable to find just inserted firewall rule for ptocol " + protocol + ", start_port " + startPort + " and end_port " + endPort + " and ip address id=" + ipId);
                }
                
                pstmt = conn.prepareStatement("select id from firewall_rules_cidrs where firewall_rule_id=?");
                pstmt.setLong(1, id);
                
                ResultSet rs2 = pstmt.executeQuery();
                
                if (rs2.next()) {
                    pstmt = conn.prepareStatement("update firewall_rules_cidrs set firewall_rule_id=? where firewall_rule_id=?");
                    pstmt.setLong(1, firewallRuleId);
                    pstmt.setLong(2, id);
                    s_logger.debug("Updating existing cidrs for the rule id=" + id + " with the new Firewall rule id=" + firewallRuleId + " with statement" + pstmt);
                    pstmt.executeUpdate();   
                } else {
                    pstmt = conn.prepareStatement("insert into firewall_rules_cidrs (firewall_rule_id,source_cidr) values (?, '0.0.0.0/0')");
                    pstmt.setLong(1, firewallRuleId);
                    s_logger.debug("Inserting rule for cidr 0.0.0.0/0 for the new Firewall rule id=" + firewallRuleId + " with statement " + pstmt);
                    pstmt.executeUpdate();   
                }
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to update firewall rule id=" + currentRuleId, e);
        } finally {
            try {
                if (rs != null) {
                    rs.close(); 
                }
               
                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
    }
 
}