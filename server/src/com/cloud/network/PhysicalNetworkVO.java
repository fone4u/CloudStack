/**
 *  Copyright (C) 2011 Citrix Systems, Inc.  All rights reserved.
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
package com.cloud.network;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Table;
import javax.persistence.TableGenerator;

import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.GenericDao;

/**
 * NetworkConfigurationVO contains information about a specific physical network.
 *
 */
@Entity
@Table(name="physical_network")
public class PhysicalNetworkVO implements PhysicalNetwork {
    @Id
    @TableGenerator(name="physical_networks_sq", table="sequence", pkColumnName="name", valueColumnName="value", pkColumnValue="physical_networks_seq", allocationSize=1)
    @Column(name="id")
    long id;
    
    @Column(name="uuid")
    private String uuid;    

    @Column(name="data_center_id")
    long dataCenterId;

    @Column(name="vnet")
    private String vnet = null;
    
    @Column(name="speed")
    private String speed = null;
    
    @Column(name="domain_id")
    Long domainId = null;

    @Column(name="broadcast_domain_range")
    @Enumerated(value=EnumType.STRING)
    BroadcastDomainRange broadcastDomainRange;

    @Column(name="state")
    @Enumerated(value=EnumType.STRING)
    State state;
    
    @Column(name=GenericDao.REMOVED_COLUMN)
    Date removed;

    @Column(name=GenericDao.CREATED_COLUMN)
    Date created;

    @ElementCollection(targetClass = String.class, fetch=FetchType.EAGER)
    @Column(name="tag")
    @CollectionTable(name="physical_network_tags", joinColumns=@JoinColumn(name="physical_network_id"))
    List<String> tags;
    
    @ElementCollection(targetClass = String.class, fetch=FetchType.EAGER)
    @Column(name="isolation_method")
    @CollectionTable(name="physical_network_isolation_methods", joinColumns=@JoinColumn(name="physical_network_id"))
    List<String> isolationMethods;

    public PhysicalNetworkVO(){
        
    }
    
    public PhysicalNetworkVO(long dataCenterId, String vnet, String speed, Long domainId, BroadcastDomainRange broadcastDomainRange) {
        this.dataCenterId = dataCenterId;
        this.setVnet(vnet);
        this.setSpeed(speed);
        this.domainId = domainId;
        if(broadcastDomainRange != null){
            this.broadcastDomainRange = broadcastDomainRange;
        }else{
            this.broadcastDomainRange = BroadcastDomainRange.POD;
        }
        this.state = State.Disabled;
        this.uuid = UUID.randomUUID().toString();
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public List<String> getTags() {
        return tags != null ? tags : new ArrayList<String>();
    }

    public void addTag(String tag) {
        if (tags == null) {
            tags = new ArrayList<String>();
        }
        tags.add(tag);
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    @Override
    public Long getDomainId() {
        return domainId;
    }

    @Override
    public BroadcastDomainRange getBroadcastDomainRange() {
        return broadcastDomainRange;
    }

    public void setBroadcastDomainRange(BroadcastDomainRange broadcastDomainRange) {
        this.broadcastDomainRange = broadcastDomainRange;
    }

    @Override
    public int hashCode() {
        return NumbersUtil.hash(id);
    }

    @Override
    public long getDataCenterId() {
        return dataCenterId;
    }

    public Date getRemoved() {
        return removed;
    }

    public void setRemoved(Date removed) {
        this.removed = removed;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    @Override
    public List<String> getIsolationMethods() {
        return isolationMethods != null ? isolationMethods : new ArrayList<String>();
    }
    
    public void addIsolationMethod(String isolationMethod) {
        if (isolationMethods == null) {
            isolationMethods = new ArrayList<String>();
        }
        isolationMethods.add(isolationMethod);
    }

    public void setIsolationMethods(List<String> isolationMethods) {
        this.isolationMethods = isolationMethods;
    }

    public void setVnet(String vnet) {
        this.vnet = vnet;
    }

    @Override
    public String getVnet() {
        return vnet;
    }

    public void setSpeed(String speed) {
        this.speed = speed;
    }

    @Override
    public String getSpeed() {
        return speed;
    }
    
    @Override
    public String getUuid() {
        return this.uuid;
    }
    
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
}
