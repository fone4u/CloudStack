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
package com.cloud.network;

import java.net.URI;
import java.security.InvalidParameterException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.acl.ControlledEntity.ACLType;
import com.cloud.agent.AgentManager;
import com.cloud.agent.Listener;
import com.cloud.agent.api.AgentControlAnswer;
import com.cloud.agent.api.AgentControlCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.alert.AlertManager;
import com.cloud.api.commands.AssociateIPAddrCmd;
import com.cloud.api.commands.CreateNetworkCmd;
import com.cloud.api.commands.ListNetworksCmd;
import com.cloud.api.commands.RestartNetworkCmd;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.configuration.dao.ResourceLimitDao;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.event.dao.UsageEventDao;
import com.cloud.exception.AccountLimitException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ConnectionException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.AddressFormat;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork.BroadcastDomainRange;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.FirewallServiceProvider;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.PortForwardingServiceProvider;
import com.cloud.network.element.RemoteAccessVPNServiceProvider;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.element.VirtualRouterElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.lb.LoadBalancingRule.LbDestination;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.rules.StaticNatRule;
import com.cloud.network.rules.StaticNatRuleImpl;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingServiceMapVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.org.Grouping;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.DomainManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserContext;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Adapters;
import com.cloud.utils.component.Inject;
import com.cloud.utils.component.Manager;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

import edu.emory.mathcs.backport.java.util.Collections;

/**
 * NetworkManagerImpl implements NetworkManager.
 */
@Local(value = { NetworkManager.class, NetworkService.class })
public class NetworkManagerImpl implements NetworkManager, NetworkService, Manager, Listener {
    private static final Logger s_logger = Logger.getLogger(NetworkManagerImpl.class);

    String _name;
    @Inject
    DataCenterDao _dcDao = null;
    @Inject
    VlanDao _vlanDao = null;
    @Inject
    IPAddressDao _ipAddressDao = null;
    @Inject
    AccountDao _accountDao = null;
    @Inject
    DomainDao _domainDao = null;
    @Inject
    UserStatisticsDao _userStatsDao = null;
    @Inject
    EventDao _eventDao = null;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    UserVmDao _userVmDao = null;
    @Inject
    ResourceLimitDao _limitDao = null;
    @Inject
    CapacityDao _capacityDao = null;
    @Inject
    AlertManager _alertMgr;
    @Inject
    AccountManager _accountMgr;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    AccountVlanMapDao _accountVlanMapDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao = null;
    @Inject
    NetworkDao _networksDao = null;
    @Inject
    NicDao _nicDao = null;
    @Inject
    RulesManager _rulesMgr;
    @Inject
    LoadBalancingRulesManager _lbMgr;
    @Inject
    UsageEventDao _usageEventDao;
    @Inject
    RemoteAccessVpnService _vpnMgr;
    @Inject
    PodVlanMapDao _podVlanMapDao;
    @Inject(adapter = NetworkGuru.class)
    Adapters<NetworkGuru> _networkGurus;
    @Inject(adapter = NetworkElement.class)
    Adapters<NetworkElement> _networkElements;
    @Inject
    NetworkDomainDao _networkDomainDao;
    @Inject
    VMInstanceDao _vmDao;
    @Inject
    FirewallManager _firewallMgr;
    @Inject 
    FirewallRulesDao _firewallDao;
    @Inject
    ResourceLimitService _resourceLimitMgr;
    @Inject DomainRouterDao _routerDao;
    @Inject DomainManager _domainMgr;
    @Inject ProjectManager _projectMgr;
    @Inject NetworkOfferingServiceMapDao _ntwkOfferingSrvcDao;
    @Inject PhysicalNetworkDao _physicalNetworkDao;
    @Inject PhysicalNetworkServiceProviderDao _pNSPDao;
    @Inject PortForwardingRulesDao _portForwardingRulesDao;
    @Inject LoadBalancerDao _lbDao;
    @Inject PhysicalNetworkTrafficTypeDao _pNTrafficTypeDao;
    @Inject AgentManager _agentMgr;
    @Inject HostDao _hostDao;
    @Inject NetworkServiceMapDao _ntwkSrvcDao;

    private final HashMap<String, NetworkOfferingVO> _systemNetworks = new HashMap<String, NetworkOfferingVO>(5);

    ScheduledExecutorService _executor;

    SearchBuilder<AccountVO> AccountsUsingNetworkSearch;
    SearchBuilder<IPAddressVO> AssignIpAddressSearch;
    SearchBuilder<IPAddressVO> AssignIpAddressFromPodVlanSearch;
    SearchBuilder<IPAddressVO> IpAddressSearch;
    SearchBuilder<NicVO> NicForTrafficTypeSearch;

    int _networkGcWait;
    int _networkGcInterval;
    String _networkDomain;
    int _cidrLimit;
    boolean _allowSubdomainNetworkAccess;
    int _networkLockTimeout;

    private Map<String, String> _configs;


    HashMap<Long, Long> _lastNetworkIdsToFree = new HashMap<Long, Long>();

    private static HashMap<Service, List<Provider>> s_serviceToImplementedProvidersMap = new HashMap<Service, List<Provider>>();
    private static HashMap<String, String> s_providerToNetworkElementMap = new HashMap<String, String>();

    private NetworkElement getElementImplementingProvider(String providerName){
        String elementName = s_providerToNetworkElementMap.get(providerName);
        NetworkElement element = _networkElements.get(elementName);
        return element;
    }

    @Override
    public List<Service> getElementServices(Provider provider){
        NetworkElement element = getElementImplementingProvider(provider.getName());
        if(element == null){
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getName() + "'");
        }
        return new ArrayList<Service>(element.getCapabilities().keySet());
    }

    @Override
    public boolean canElementEnableIndividualServices(Provider provider){
        NetworkElement element = getElementImplementingProvider(provider.getName());
        if(element == null){
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getName() + "'");
        }
        return element.canEnableIndividualServices();
    }

    @Override
    public PublicIp assignPublicIpAddress(long dcId, Long podId, Account owner, VlanType type, Long networkId, String requestedIp) throws InsufficientAddressCapacityException {
        return fetchNewPublicIp(dcId, podId, null, owner, type, networkId, false, true, requestedIp);
    }

    @DB
    public PublicIp fetchNewPublicIp(long dcId, Long podId, Long vlanDbId, Account owner, VlanType vlanUse, Long networkId, boolean sourceNat, boolean assign, String requestedIp)
            throws InsufficientAddressCapacityException {
        StringBuilder errorMessage = new StringBuilder("Unable to get ip adress in ");
        Transaction txn = Transaction.currentTxn();
        txn.start();
        SearchCriteria<IPAddressVO> sc = null;
        if (podId != null) {
            sc = AssignIpAddressFromPodVlanSearch.create();
            sc.setJoinParameters("podVlanMapSB", "podId", podId);
            errorMessage.append(" pod id=" + podId);
        } else {
            sc = AssignIpAddressSearch.create();
            errorMessage.append(" zone id=" + dcId);
        }

        if (vlanDbId != null) {
            sc.addAnd("vlanId", SearchCriteria.Op.EQ, vlanDbId);
            errorMessage.append(", vlanId id=" + vlanDbId);
        }

        sc.setParameters("dc", dcId);

        // for direct network take ip addresses only from the vlans belonging to the network
        if (vlanUse == VlanType.DirectAttached) {
            sc.setJoinParameters("vlan", "networkId", networkId);
            errorMessage.append(", network id=" + networkId);
        }
        sc.setJoinParameters("vlan", "type", vlanUse);


        if (requestedIp != null) {
            sc.addAnd("address", SearchCriteria.Op.EQ, requestedIp);
            errorMessage.append(": requested ip " + requestedIp + " is not available");
        }

        Filter filter = new Filter(IPAddressVO.class, "vlanId", true, 0l, 1l);

        List<IPAddressVO> addrs = _ipAddressDao.lockRows(sc, filter, true);

        if (addrs.size() == 0) {
            if (podId != null) {
                throw new InsufficientAddressCapacityException("Insufficient address capacity", Pod.class, podId);
            }
            s_logger.warn(errorMessage.toString());
            throw new InsufficientAddressCapacityException("Insufficient address capacity", DataCenter.class, dcId);
        }

        assert (addrs.size() == 1) : "Return size is incorrect: " + addrs.size();

        IPAddressVO addr = addrs.get(0);
        addr.setSourceNat(sourceNat);
        addr.setAllocatedTime(new Date());
        addr.setAllocatedInDomainId(owner.getDomainId());
        addr.setAllocatedToAccountId(owner.getId());

        if (assign) {
            markPublicIpAsAllocated(addr);
        } else {
            addr.setState(IpAddress.State.Allocating);
        }
        addr.setState(assign ? IpAddress.State.Allocated : IpAddress.State.Allocating);

        if (vlanUse != VlanType.DirectAttached) {
            addr.setAssociatedWithNetworkId(networkId);
        }

        _ipAddressDao.update(addr.getId(), addr);

        txn.commit();

        if (vlanUse == VlanType.VirtualNetwork) {
            _firewallMgr.addSystemFirewallRules(addr, owner);
        }

        long macAddress = NetUtils.createSequenceBasedMacAddress(addr.getMacAddress());



        return new PublicIp(addr, _vlanDao.findById(addr.getVlanId()), macAddress);
    }

    @DB
    protected void markPublicIpAsAllocated(IPAddressVO addr) {

        assert (addr.getState() == IpAddress.State.Allocating || addr.getState() == IpAddress.State.Free) : "Unable to transition from state " + addr.getState() + " to " + IpAddress.State.Allocated;

        Transaction txn = Transaction.currentTxn();

        Account owner = _accountMgr.getAccount(addr.getAccountId());
        long isSourceNat = (addr.isSourceNat()) ? 1 : 0;

        txn.start();
        addr.setState(IpAddress.State.Allocated);
        _ipAddressDao.update(addr.getId(), addr);

        // Save usage event
        if (owner.getAccountId() != Account.ACCOUNT_ID_SYSTEM) {
            VlanVO vlan = _vlanDao.findById(addr.getVlanId());

            String guestType = vlan.getVlanType().toString();

            UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_NET_IP_ASSIGN, owner.getId(), addr.getDataCenterId(), addr.getId(), addr.getAddress().toString(), isSourceNat, guestType);
            _usageEventDao.persist(usageEvent);
            // don't increment resource count for direct ip addresses
            if (addr.getAssociatedWithNetworkId() != null) {
                _resourceLimitMgr.incrementResourceCount(owner.getId(), ResourceType.public_ip);
            }
        }

        txn.commit();
    }

    @Override
    @DB
    public PublicIp assignSourceNatIpAddress(Account owner, Network network, long callerId) throws ConcurrentOperationException, InsufficientAddressCapacityException {
        assert (network.getTrafficType() != null) : "You're asking for a source nat but your network can't participate in source nat.  What do you have to say for yourself?";

        long dcId = network.getDataCenterId();
        long ownerId = owner.getId();

        PublicIp ip = null;

        Transaction txn = Transaction.currentTxn();
        try {
            txn.start();

            owner = _accountDao.acquireInLockTable(ownerId);

            if (owner == null) {
                throw new ConcurrentOperationException("Unable to lock account " + ownerId);
            }
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("lock account " + ownerId + " is acquired");
            }

            IPAddressVO sourceNat = null;
            List<IPAddressVO> addrs = listPublicIpAddressesInVirtualNetwork(ownerId, dcId, null, network.getId());
            if (addrs.size() == 0) {

                // Check that the maximum number of public IPs for the given accountId will not be exceeded
                try {
                    _resourceLimitMgr.checkResourceLimit(owner, ResourceType.public_ip); 
                } catch (ResourceAllocationException ex) {
                    s_logger.warn("Failed to allocate resource of type " + ex.getResourceType() + " for account " + owner);
                    throw new AccountLimitException("Maximum number of public IP addresses for account: " + owner.getAccountName() + " has been exceeded.");
                }

                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("assigning a new ip address in " + dcId + " to " + owner);
                }

                // If account has Account specific ip ranges, try to allocate ip from there
                Long vlanId = null;
                List<AccountVlanMapVO> maps = _accountVlanMapDao.listAccountVlanMapsByAccount(ownerId);
                if (maps != null && !maps.isEmpty()) {
                    vlanId = maps.get(0).getVlanDbId();
                }

                ip = fetchNewPublicIp(dcId, null, vlanId, owner, VlanType.VirtualNetwork, network.getId(), true, false, null);
                sourceNat = ip.ip();

                markPublicIpAsAllocated(sourceNat);
                _ipAddressDao.update(sourceNat.getId(), sourceNat);
            } else {
                // Account already has ip addresses
                for (IPAddressVO addr : addrs) {
                    if (addr.isSourceNat()) {
                        sourceNat = addr;
                        break;
                    }
                }

                assert (sourceNat != null) : "How do we get a bunch of ip addresses but none of them are source nat? account=" + ownerId + "; dc=" + dcId;
                ip = new PublicIp(sourceNat, _vlanDao.findById(sourceNat.getVlanId()), NetUtils.createSequenceBasedMacAddress(sourceNat.getMacAddress()));
            }

            txn.commit();
            return ip;
        } finally {
            if (owner != null) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Releasing lock account " + ownerId);
                }

                _accountDao.releaseFromLockTable(ownerId);
            }
            if (ip == null) {
                txn.rollback();
                s_logger.error("Unable to get source nat ip address for account " + ownerId);
            }
        }
    }

    /**
     * Returns the target account for an api command
     *
     * @param accountName
     *            - non-null if the account name was passed in in the command
     * @param domainId
     *            - non-null if the domainId was passed in in the command.
     * @return
     */
    protected Account getAccountForApiCommand(String accountName, Long domainId) {
        Account account = UserContext.current().getCaller();

        if (_accountMgr.isAdmin(account.getType())) {
            // The admin is making the call, determine if it is for someone else or for himself
            if (domainId != null) {
                if ((account != null) && !_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                    throw new PermissionDeniedException("Invalid domain id (" + domainId + ") given, , permission denied");
                }
                if (accountName != null) {
                    Account userAccount = _accountMgr.getActiveAccountByName(accountName, domainId);
                    if (userAccount != null) {
                        account = userAccount;
                    } else {
                        throw new PermissionDeniedException("Unable to find account " + accountName + " in domain " + domainId + ", permission denied");
                    }
                }
            } else {
                // the admin is calling the api on his own behalf
                return account;
            }
        }
        return account;
    }

    @Override
    public boolean applyIpAssociations(Network network, boolean continueOnError) throws ResourceUnavailableException {
        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        List<PublicIp> publicIps = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (IPAddressVO userIp : userIps) {
                PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
                publicIps.add(publicIp);
            }
        }

        boolean success = applyIpAssociations(network, continueOnError, publicIps);

        if (success) {
            for (IPAddressVO addr : userIps) {

                if (addr.getState() == IpAddress.State.Allocating) {

                    addr.setAssociatedWithNetworkId(network.getId());
                    markPublicIpAsAllocated(addr);

                } else if (addr.getState() == IpAddress.State.Releasing) {
                    // Cleanup all the resources for ip address if there are any, and only then un-assign ip in the system
                    if (cleanupIpResources(addr.getId(), Account.ACCOUNT_ID_SYSTEM, _accountMgr.getSystemAccount())) {
                        _ipAddressDao.unassignIpAddress(addr.getId());
                    } else {
                        success = false;
                        s_logger.warn("Failed to release resources for ip address id=" + addr.getId());
                    }
                }
            }
        }

        return success;
    }

    protected boolean applyIpAssociations(Network network, boolean continueOnError, List<PublicIp> publicIps) throws ResourceUnavailableException {
        boolean success = true;
        for (NetworkElement element : _networkElements) {
            try {
                if (!(element instanceof FirewallServiceProvider)) {
                    continue;
                }
                FirewallServiceProvider e = (FirewallServiceProvider)element;
                s_logger.trace("Asking " + element + " to apply ip associations");
                e.applyIps(network, publicIps);
            } catch (ResourceUnavailableException e) {
                success = false;
                if (!continueOnError) {
                    throw e;
                } else {
                    s_logger.debug("Resource is not available: " + element.getName(), e);
                }
            }
        }
        return success;
    }



    @Override
    public List<? extends Network> getIsolatedNetworksOwnedByAccountInZone(long zoneId, Account owner) {

        return _networksDao.listBy(owner.getId(), zoneId, Network.GuestType.Isolated);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_ASSIGN, eventDescription = "allocating Ip", create = true)
    public IpAddress allocateIP(AssociateIPAddrCmd cmd) throws ResourceAllocationException, InsufficientAddressCapacityException, ConcurrentOperationException {
        String accountName = cmd.getAccountName();
        long domainId = cmd.getDomainId();
        Long zoneId = cmd.getZoneId();
        Account caller = UserContext.current().getCaller();
        long userId = UserContext.current().getCallerUserId();

        Account ipOwner = _accountMgr.getActiveAccountByName(accountName, domainId);
        if (ipOwner == null) {
            throw new InvalidParameterValueException("Unable to find account " + accountName + " in domain " + domainId + ", permission denied");
        }

        _accountMgr.checkAccess(caller, null, ipOwner);

        DataCenterVO zone = null;
        if (zoneId != null) {
            zone = _dcDao.findById(zoneId);
            if (zone == null) {
                throw new InvalidParameterValueException("Can't find zone by id " + zoneId);
            }

            if (zone.getNetworkType() == NetworkType.Basic) {
                throw new InvalidParameterValueException("Can't associate ip in basic zone");
            }

            if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getType())) {
                throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zoneId);
            }
        }
        long ownerId = ipOwner.getId();
        Long networkId = cmd.getNetworkId();
        Network network = null;
        if (networkId != null) {
            network = _networksDao.findById(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Network id is invalid: " + networkId);
            }
        }

        // Check that network belongs to IP owner - skip this check for Basic zone as there is just one guest network, and it
        // belongs to the system
        if (zone.getNetworkType() != NetworkType.Basic && network.getAccountId() != ipOwner.getId()) {
            throw new InvalidParameterValueException("The owner of the network is not the same as owner of the IP");
        }
        VlanType vlanType = VlanType.VirtualNetwork;
        boolean assign = false;
        //For basic zone, if there isn't a public network outside of the guest network, specify the vlan type to be direct attached
        if (zone.getNetworkType() == NetworkType.Basic) {
            if (network.getTrafficType() == TrafficType.Guest){
                vlanType = VlanType.DirectAttached;
                assign = true;
            }

        }

        PublicIp ip = null;

        Transaction txn = Transaction.currentTxn();
        Account accountToLock = null;
        try {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Associate IP address called for user " + userId + " account " + ownerId);
            }
            accountToLock = _accountDao.acquireInLockTable(ownerId);
            if (accountToLock == null) {
                s_logger.warn("Unable to lock account: " + ownerId);
                throw new ConcurrentOperationException("Unable to acquire account lock");
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Associate IP address lock acquired");
            }

            // Check that the maximum number of public IPs for the given
            // accountId will not be exceeded
            _resourceLimitMgr.checkResourceLimit(accountToLock, ResourceType.public_ip);

            boolean isSourceNat = false;

            txn.start();

            boolean sharedSourceNat = false;
            Map<Network.Capability, String> sourceNatCapabilities = getNetworkServiceCapabilities(network.getId(), Service.SourceNat);
            if (sourceNatCapabilities != null) {
                String supportedSourceNatTypes = sourceNatCapabilities.get(Capability.SupportedSourceNatTypes).toLowerCase();
                if (supportedSourceNatTypes.contains("zone")) {
                    sharedSourceNat = true;
                }
            }

            if (!sharedSourceNat) {
                // First IP address should be source nat when it's being associated with Guest Virtual network
                List<IPAddressVO> addrs = listPublicIpAddressesInVirtualNetwork(ownerId, zoneId, true, networkId);

                if (addrs.isEmpty() && network.getGuestType() == Network.GuestType.Isolated) {
                    isSourceNat = true;
                }
            }

            ip = fetchNewPublicIp(zoneId, null, null, ipOwner, vlanType, network.getId(), isSourceNat, assign, null);

            if (ip == null) {
                throw new InsufficientAddressCapacityException("Unable to find available public IP addresses", DataCenter.class, zoneId);
            }
            UserContext.current().setEventDetails("Ip Id: " + ip.getId());
            Ip ipAddress = ip.getAddress();

            s_logger.debug("Got " + ipAddress + " to assign for account " + ipOwner.getId() + " in zone " + network.getDataCenterId());

            txn.commit();
        } finally {
            if (accountToLock != null) {
                _accountDao.releaseFromLockTable(ownerId);
                s_logger.debug("Associate IP address lock released");
            }
        }

        return ip;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_ASSIGN, eventDescription = "associating Ip", async = true)
    public IpAddress associateIP(AssociateIPAddrCmd cmd) throws ResourceAllocationException, ResourceUnavailableException, InsufficientAddressCapacityException, ConcurrentOperationException {
        Account caller = UserContext.current().getCaller();
        Account owner = null;

        IpAddress ipToAssoc = getIp(cmd.getEntityId());
        if (ipToAssoc != null) {
            _accountMgr.checkAccess(caller, null, ipToAssoc);
            owner = _accountMgr.getAccount(ipToAssoc.getAccountId());
        } else {
            s_logger.debug("Unable to find ip address by id: " + cmd.getEntityId());
            return null;
        }

        Network network = _networksDao.findById(ipToAssoc.getAssociatedWithNetworkId());

        IPAddressVO ip = _ipAddressDao.findById(cmd.getEntityId());
        boolean success = false;
        try {
            success = applyIpAssociations(network, false);
            if (success) {
                s_logger.debug("Successfully associated ip address " + ip.getAddress().addr() + " for account " + owner.getId() + " in zone " + network.getDataCenterId());
            } else {
                s_logger.warn("Failed to associate ip address " + ip.getAddress().addr() + " for account " + owner.getId() + " in zone " + network.getDataCenterId());
            }
            return ip;
        } catch (ResourceUnavailableException e) {
            s_logger.error("Unable to associate ip address due to resource unavailable exception", e);
            return null;
        } finally {
            if (!success) {
                if (ip != null) {
                    try {
                        s_logger.warn("Failed to associate ip address " + ip);
                        _ipAddressDao.markAsUnavailable(ip.getId());
                        if (!applyIpAssociations(network, true)) {
                            // if fail to apply ip assciations again, unassign ip address without updating resource count and
                            // generating usage event as there is no need to keep it in the db
                            _ipAddressDao.unassignIpAddress(ip.getId());
                        }
                    } catch (Exception e) {
                        s_logger.warn("Unable to disassociate ip address for recovery", e);
                    }
                }
            }
        }
    }

    @Override
    @DB
    public boolean releasePublicIpAddress(long addrId, long userId, Account caller) {

        IPAddressVO ip = markIpAsUnavailable(addrId);

        assert (ip != null) : "Unable to mark the ip address id=" + addrId + " as unavailable.";
        if (ip == null) {
            return true;
        }

        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing ip id=" + addrId + "; sourceNat = " + ip.isSourceNat());
        }

        boolean success = true;

        // Cleanup all ip address resources - PF/LB/Static nat rules
        if (!cleanupIpResources(addrId, userId, caller)) {
            success = false;
            s_logger.warn("Failed to release resources for ip address id=" + addrId);
        }

        if (ip.getAssociatedWithNetworkId() != null) {
            Network network = _networksDao.findById(ip.getAssociatedWithNetworkId());
            try {
                if (!applyIpAssociations(network, true)) {
                    s_logger.warn("Unable to apply ip address associations for " + network);
                    success = false;
                }
            } catch (ResourceUnavailableException e) {
                throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
            }
        }

        if (success) {
            s_logger.debug("released a public ip id=" + addrId);
        }

        return success;
    }

    @Override @DB
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _name = name;

        _configs = _configDao.getConfiguration("AgentManager", params);
        _networkGcWait = NumbersUtil.parseInt(_configs.get(Config.NetworkGcWait.key()), 600);
        _networkGcInterval = NumbersUtil.parseInt(_configs.get(Config.NetworkGcInterval.key()), 600);

        _configs = _configDao.getConfiguration("Network", params);
        _networkDomain = _configs.get(Config.GuestDomainSuffix.key());

        _cidrLimit = NumbersUtil.parseInt(_configs.get(Config.NetworkGuestCidrLimit.key()), 22);
        _networkLockTimeout = NumbersUtil.parseInt(_configs.get(Config.NetworkLockTimeout.key()), 600);

        NetworkOfferingVO publicNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemPublicNetwork, TrafficType.Public);
        publicNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(publicNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemPublicNetwork, publicNetworkOffering);
        NetworkOfferingVO managementNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemManagementNetwork, TrafficType.Management);
        managementNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(managementNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemManagementNetwork, managementNetworkOffering);
        NetworkOfferingVO controlNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemControlNetwork, TrafficType.Control);
        controlNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(controlNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemControlNetwork, controlNetworkOffering);
        NetworkOfferingVO storageNetworkOffering = new NetworkOfferingVO(NetworkOfferingVO.SystemStorageNetwork, TrafficType.Storage);
        storageNetworkOffering = _networkOfferingDao.persistDefaultNetworkOffering(storageNetworkOffering);
        _systemNetworks.put(NetworkOfferingVO.SystemStorageNetwork, storageNetworkOffering);

        //populate providers
        Map<Network.Service, Set<Network.Provider>> defaultSharedNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        Set<Network.Provider> defaultProviders = new HashSet<Network.Provider>();

        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultSharedNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedNetworkOfferingProviders.put(Service.UserData, defaultProviders);

        Map<Network.Service, Set<Network.Provider>> defaultIsolatedNetworkOfferingProviders = defaultSharedNetworkOfferingProviders;

        Map<Network.Service, Set<Network.Provider>> defaultSharedSGEnabledNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        Set<Provider> sgProviders = new HashSet<Provider>();
        sgProviders.add(Provider.SecurityGroupProvider);
        defaultSharedSGEnabledNetworkOfferingProviders.put(Service.SecurityGroup, sgProviders);

        Map<Network.Service, Set<Network.Provider>> defaultIsolatedSourceNatEnabledNetworkOfferingProviders = new HashMap<Network.Service, Set<Network.Provider>>();
        defaultProviders.clear();
        defaultProviders.add(Network.Provider.VirtualRouter);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dhcp, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Dns, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.UserData, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Firewall, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Gateway, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Lb, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.SourceNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.StaticNat, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.PortForwarding, defaultProviders);
        defaultIsolatedSourceNatEnabledNetworkOfferingProviders.put(Service.Vpn, defaultProviders);

        Transaction txn = Transaction.currentTxn();
        txn.start();
        //diff between offering #1 and #2 - securityGroup is enabled for the first, and disabled for the third

        NetworkOfferingVO offering = null;
        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedNetworkOfferingWithSGService) == null) {
            offering = _configMgr.createNetworkOffering(Account.ACCOUNT_ID_SYSTEM, NetworkOffering.DefaultSharedNetworkOfferingWithSGService, "Offering for Shared Security group enabled networks", TrafficType.Guest, null, null, true, Availability.Optional, null, defaultSharedNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, null);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }

        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultSharedNetworkOffering) == null) {
            offering = _configMgr.createNetworkOffering(Account.ACCOUNT_ID_SYSTEM, NetworkOffering.DefaultSharedNetworkOffering, "Offering for Shared networks", TrafficType.Guest, null, null, true, Availability.Optional, null, defaultSharedNetworkOfferingProviders, true, Network.GuestType.Shared, false, null, null);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }

        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService) == null) {
            offering = _configMgr.createNetworkOffering(Account.ACCOUNT_ID_SYSTEM,NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService, "Offering for Isolated networks with Source Nat service enabled", TrafficType.Guest, null, null, false, Availability.Required, null, defaultIsolatedSourceNatEnabledNetworkOfferingProviders, true, Network.GuestType.Isolated, false, null, null);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        } 

        if (_networkOfferingDao.findByUniqueName(NetworkOffering.DefaultIsolatedNetworkOffering) == null) {
            offering = _configMgr.createNetworkOffering(Account.ACCOUNT_ID_SYSTEM, NetworkOffering.DefaultIsolatedNetworkOffering, "Offering for Isolated networks with no Source Nat service", TrafficType.Guest, null, null, true, Availability.Optional, null, defaultIsolatedNetworkOfferingProviders, true, Network.GuestType.Isolated, false, null, null);
            offering.setState(NetworkOffering.State.Enabled);
            _networkOfferingDao.update(offering.getId(), offering);
        }

        txn.commit();

        AccountsUsingNetworkSearch = _accountDao.createSearchBuilder();
        SearchBuilder<NetworkAccountVO> networkAccountSearch = _networksDao.createSearchBuilderForAccount();
        AccountsUsingNetworkSearch.join("nc", networkAccountSearch, AccountsUsingNetworkSearch.entity().getId(), networkAccountSearch.entity().getAccountId(), JoinType.INNER);
        networkAccountSearch.and("config", networkAccountSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        networkAccountSearch.and("owner", networkAccountSearch.entity().isOwner(), SearchCriteria.Op.EQ);
        AccountsUsingNetworkSearch.done();

        AssignIpAddressSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressSearch.and("dc", AssignIpAddressSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressSearch.and("allocated", AssignIpAddressSearch.entity().getAllocatedTime(), Op.NULL);
        AssignIpAddressSearch.and("vlanId", AssignIpAddressSearch.entity().getVlanId(), Op.EQ);
        SearchBuilder<VlanVO> vlanSearch = _vlanDao.createSearchBuilder();
        vlanSearch.and("type", vlanSearch.entity().getVlanType(), Op.EQ);
        vlanSearch.and("networkId", vlanSearch.entity().getNetworkId(), Op.EQ);
        AssignIpAddressSearch.join("vlan", vlanSearch, vlanSearch.entity().getId(), AssignIpAddressSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressSearch.done();

        AssignIpAddressFromPodVlanSearch = _ipAddressDao.createSearchBuilder();
        AssignIpAddressFromPodVlanSearch.and("dc", AssignIpAddressFromPodVlanSearch.entity().getDataCenterId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.and("allocated", AssignIpAddressFromPodVlanSearch.entity().getAllocatedTime(), Op.NULL);
        SearchBuilder<VlanVO> podVlanSearch = _vlanDao.createSearchBuilder();
        podVlanSearch.and("type", podVlanSearch.entity().getVlanType(), Op.EQ);
        podVlanSearch.and("networkId", podVlanSearch.entity().getNetworkId(), Op.EQ);
        SearchBuilder<PodVlanMapVO> podVlanMapSB = _podVlanMapDao.createSearchBuilder();
        podVlanMapSB.and("podId", podVlanMapSB.entity().getPodId(), Op.EQ);
        AssignIpAddressFromPodVlanSearch.join("podVlanMapSB", podVlanMapSB, podVlanMapSB.entity().getVlanDbId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressFromPodVlanSearch.join("vlan", podVlanSearch, podVlanSearch.entity().getId(), AssignIpAddressFromPodVlanSearch.entity().getVlanId(), JoinType.INNER);
        AssignIpAddressFromPodVlanSearch.done();

        IpAddressSearch = _ipAddressDao.createSearchBuilder();
        IpAddressSearch.and("accountId", IpAddressSearch.entity().getAllocatedToAccountId(), Op.EQ);
        IpAddressSearch.and("dataCenterId", IpAddressSearch.entity().getDataCenterId(), Op.EQ);
        IpAddressSearch.and("associatedWithNetworkId", IpAddressSearch.entity().getAssociatedWithNetworkId(), Op.EQ);
        SearchBuilder<VlanVO> virtualNetworkVlanSB = _vlanDao.createSearchBuilder();
        virtualNetworkVlanSB.and("vlanType", virtualNetworkVlanSB.entity().getVlanType(), Op.EQ);
        IpAddressSearch.join("virtualNetworkVlanSB", virtualNetworkVlanSB, IpAddressSearch.entity().getVlanId(), virtualNetworkVlanSB.entity().getId(), JoinBuilder.JoinType.INNER);
        IpAddressSearch.done();

        NicForTrafficTypeSearch = _nicDao.createSearchBuilder();
        SearchBuilder<NetworkVO> networkSearch = _networksDao.createSearchBuilder();
        NicForTrafficTypeSearch.join("network", networkSearch, networkSearch.entity().getId(), NicForTrafficTypeSearch.entity().getNetworkId(), JoinType.INNER);
        NicForTrafficTypeSearch.and("instance", NicForTrafficTypeSearch.entity().getInstanceId(), Op.EQ);
        networkSearch.and("traffictype", networkSearch.entity().getTrafficType(), Op.EQ);
        NicForTrafficTypeSearch.done();

        _executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Network-Scavenger"));

        _allowSubdomainNetworkAccess = Boolean.valueOf(_configs.get(Config.SubDomainNetworkAccess.key()));

        _agentMgr.registerForHostEvents(this, true, false, true);

        s_logger.info("Network Manager is configured.");

        return true;
    }

    @Override
    public String getName() {
        return _name;
    }

    @Override
    public boolean start() {

        //populate s_serviceToImplementedProvidersMap & s_providerToNetworkElementMap with current _networkElements
        //Need to do this in start() since _networkElements are not completely configured until then.
        for (NetworkElement element : _networkElements) {
            Map<Service, Map<Capability, String>> capabilities = element.getCapabilities();
            Provider implementedProvider = element.getProvider();
            if(implementedProvider != null){
                if(s_providerToNetworkElementMap.containsKey(implementedProvider.getName())){
                    s_logger.error("Cannot start NetworkManager: Provider <-> NetworkElement must be a one-to-one map, multiple NetworkElements found for Provider: "+implementedProvider.getName());
                    return false;
                }
                s_providerToNetworkElementMap.put(implementedProvider.getName(), element.getName());
            }
            if(capabilities != null && implementedProvider != null){
                for(Service service : capabilities.keySet()){
                    if(s_serviceToImplementedProvidersMap.containsKey(service)){
                        List<Provider> providers = s_serviceToImplementedProvidersMap.get(service);
                        providers.add(implementedProvider);
                    }else{
                        List<Provider> providers = new ArrayList<Provider>();
                        providers.add(implementedProvider);
                        s_serviceToImplementedProvidersMap.put(service, providers);
                    }
                }
            }
        }

        _executor.scheduleWithFixedDelay(new NetworkGarbageCollector(), _networkGcInterval, _networkGcInterval, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    protected NetworkManagerImpl() {
    }

    @Override
    public List<IPAddressVO> listPublicIpAddressesInVirtualNetwork(long accountId, long dcId, Boolean sourceNat, Long associatedNetworkId) {
        SearchCriteria<IPAddressVO> sc = IpAddressSearch.create();
        sc.setParameters("accountId", accountId);
        sc.setParameters("dataCenterId", dcId);
        if (associatedNetworkId != null) {
            sc.setParameters("associatedWithNetworkId", associatedNetworkId);
        }

        if (sourceNat != null) {
            sc.addAnd("sourceNat", SearchCriteria.Op.EQ, sourceNat);
        }
        sc.setJoinParameters("virtualNetworkVlanSB", "vlanType", VlanType.VirtualNetwork);

        return _ipAddressDao.search(sc, null);
    }

    @Override
    public List<NetworkVO> setupNetwork(Account owner, NetworkOfferingVO offering, DeploymentPlan plan, String name, String displayText, boolean isDefault)
            throws ConcurrentOperationException {
        return setupNetwork(owner, offering, null, plan, name, displayText, isDefault, false, null, null, null);
    }

    @Override
    @DB
    public List<NetworkVO> setupNetwork(Account owner, NetworkOfferingVO offering, Network predefined, DeploymentPlan plan, String name, String displayText, boolean isDefault, boolean errorIfAlreadySetup,
            Long domainId, ACLType aclType, Boolean subdomainAccess) throws ConcurrentOperationException {
        Account locked = _accountDao.acquireInLockTable(owner.getId());
        if (locked == null) {
            throw new ConcurrentOperationException("Unable to acquire lock on " + owner);
        }

        try {
            if (predefined == null || (predefined.getCidr() == null && predefined.getBroadcastUri() == null && predefined.getBroadcastDomainType() != BroadcastDomainType.Vlan)) {
                List<NetworkVO> configs = _networksDao.listBy(owner.getId(), offering.getId(), plan.getDataCenterId());
                if (configs.size() > 0) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Found existing network configuration for offering " + offering + ": " + configs.get(0));
                    }

                    if (errorIfAlreadySetup) {
                        throw new InvalidParameterValueException("Found existing network configuration for offering " + offering + ": " + configs.get(0));
                    } else {
                        return configs;
                    }
                }
            } else if (predefined != null && predefined.getCidr() != null && predefined.getBroadcastUri() == null && predefined.getBroadcastUri() == null) {
                List<NetworkVO> configs = _networksDao.listBy(owner.getId(), offering.getId(), plan.getDataCenterId(), predefined.getCidr());
                if (configs.size() > 0) {
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Found existing network configuration for offering " + offering + ": " + configs.get(0));
                    }

                    if (errorIfAlreadySetup) {
                        throw new InvalidParameterValueException("Found existing network configuration for offering " + offering + ": " + configs.get(0));
                    } else {
                        return configs;
                    }
                }
            }

            List<NetworkVO> networks = new ArrayList<NetworkVO>();

            long related = -1;

            for (NetworkGuru guru : _networkGurus) {
                Network network = guru.design(offering, plan, predefined, owner);
                if (network == null) {
                    continue;
                }

                if (network.getId() != -1) {
                    if (network instanceof NetworkVO) {
                        networks.add((NetworkVO) network);
                    } else {
                        networks.add(_networksDao.findById(network.getId()));
                    }
                    continue;
                }

                long id = _networksDao.getNextInSequence(Long.class, "id");
                if (related == -1) {
                    related = id;
                }

                NetworkVO vo = new NetworkVO(id, network, offering.getId(), guru.getName(), owner.getDomainId(), owner.getId(), related, name, displayText, isDefault,
                        predefined.getNetworkDomain(), offering.getGuestType(), plan.getDataCenterId(), plan.getPhysicalNetworkId(), aclType);
                networks.add(_networksDao.persist(vo, vo.getGuestType() == Network.GuestType.Isolated, finalizeServicesAndProvidersForNetwork(offering, plan.getPhysicalNetworkId())));

                if (domainId != null && aclType == ACLType.Domain) {
                    _networksDao.addDomainToNetwork(id, domainId, subdomainAccess);
                }
            }

            if (networks.size() < 1) {
                throw new CloudRuntimeException("Unable to convert network offering to network profile: " + offering.getId());
            }

            return networks;
        } finally {
            s_logger.debug("Releasing lock for " + locked);
            _accountDao.releaseFromLockTable(locked.getId());
        }
    }

    @Override
    public List<NetworkOfferingVO> getSystemAccountNetworkOfferings(String... offeringNames) {
        List<NetworkOfferingVO> offerings = new ArrayList<NetworkOfferingVO>(offeringNames.length);
        for (String offeringName : offeringNames) {
            NetworkOfferingVO network = _systemNetworks.get(offeringName);
            if (network == null) {
                throw new CloudRuntimeException("Unable to find system network profile for " + offeringName);
            }
            offerings.add(network);
        }
        return offerings;
    }

    @Override
    @DB
    public void allocate(VirtualMachineProfile<? extends VMInstanceVO> vm, List<Pair<NetworkVO, NicProfile>> networks) throws InsufficientCapacityException, ConcurrentOperationException {
        Transaction txn = Transaction.currentTxn();
        txn.start();

        int deviceId = 0;

        boolean[] deviceIds = new boolean[networks.size()];
        Arrays.fill(deviceIds, false);

        List<NicVO> nics = new ArrayList<NicVO>(networks.size());
        NicVO defaultNic = null;

        for (Pair<NetworkVO, NicProfile> network : networks) {
            NetworkVO config = network.first();
            NetworkGuru guru = _networkGurus.get(config.getGuruName());
            NicProfile requested = network.second();
            if (requested != null && requested.getMode() == null) {
                requested.setMode(config.getMode());
            }
            NicProfile profile = guru.allocate(config, requested, vm);

            if (vm != null && vm.getVirtualMachine().getType() == Type.User && config.isDefault()) {
                profile.setDefaultNic(true);
            }

            if (profile == null) {
                continue;
            }

            if (requested != null && requested.getMode() == null) {
                profile.setMode(requested.getMode());
            } else {
                profile.setMode(config.getMode());
            }

            NicVO vo = new NicVO(guru.getName(), vm.getId(), config.getId(), vm.getType());

            while (deviceIds[deviceId] && deviceId < deviceIds.length) {
                deviceId++;
            }

            deviceId = applyProfileToNic(vo, profile, deviceId);

            vo = _nicDao.persist(vo);

            if (vo.isDefaultNic()) {
                if (defaultNic != null) {
                    throw new IllegalArgumentException("You cannot specify two nics as default nics: nic 1 = " + defaultNic + "; nic 2 = " + vo);
                }
                defaultNic = vo;
            }

            int devId = vo.getDeviceId();
            if (devId > deviceIds.length) {
                throw new IllegalArgumentException("Device id for nic is too large: " + vo);
            }
            if (deviceIds[devId]) {
                throw new IllegalArgumentException("Conflicting device id for two different nics: " + devId);
            }

            deviceIds[devId] = true;
            nics.add(vo);

            Integer networkRate = getNetworkRate(config.getId(), vm.getId());
            vm.addNic(new NicProfile(vo, network.first(), vo.getBroadcastUri(), vo.getIsolationUri(), networkRate, isSecurityGroupSupportedInNetwork(network.first()), getNetworkTag(vm.getHypervisorType(), network.first())));
        }

        if (nics.size() != networks.size()) {
            s_logger.warn("Number of nics " + nics.size() + " doesn't match number of requested networks " + networks.size());
            throw new CloudRuntimeException("Number of nics " + nics.size() + " doesn't match number of requested networks " + networks.size());
        }

        if (nics.size() == 1) {
            nics.get(0).setDefaultNic(true);
        }

        txn.commit();
    }

    protected Integer applyProfileToNic(NicVO vo, NicProfile profile, Integer deviceId) {
        if (profile.getDeviceId() != null) {
            vo.setDeviceId(profile.getDeviceId());
        } else if (deviceId != null) {
            vo.setDeviceId(deviceId++);
        }

        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }

        vo.setDefaultNic(profile.isDefaultNic());

        if (profile.getIp4Address() != null) {
            vo.setIp4Address(profile.getIp4Address());
            vo.setAddressFormat(AddressFormat.Ip4);
        }

        if (profile.getMacAddress() != null) {
            vo.setMacAddress(profile.getMacAddress());
        }

        vo.setMode(profile.getMode());
        vo.setNetmask(profile.getNetmask());
        vo.setGateway(profile.getGateway());

        if (profile.getBroadCastUri() != null) {
            vo.setBroadcastUri(profile.getBroadCastUri());
        }

        if (profile.getIsolationUri() != null) {
            vo.setIsolationUri(profile.getIsolationUri());
        }

        vo.setState(Nic.State.Allocated);
        return deviceId;
    }

    protected void applyProfileToNicForRelease(NicVO vo, NicProfile profile) {
        vo.setGateway(profile.getGateway());
        vo.setAddressFormat(profile.getFormat());
        vo.setIp4Address(profile.getIp4Address());
        vo.setIp6Address(profile.getIp6Address());
        vo.setMacAddress(profile.getMacAddress());
        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }
        vo.setBroadcastUri(profile.getBroadCastUri());
        vo.setIsolationUri(profile.getIsolationUri());
        vo.setNetmask(profile.getNetmask());
    }

    protected void applyProfileToNetwork(NetworkVO network, NetworkProfile profile) {
        network.setBroadcastUri(profile.getBroadcastUri());
        network.setDns1(profile.getDns1());
        network.setDns2(profile.getDns2());
        network.setPhysicalNetworkId(profile.getPhysicalNetworkId());
    }

    protected NicTO toNicTO(NicVO nic, NicProfile profile, NetworkVO config) {
        NicTO to = new NicTO();
        to.setDeviceId(nic.getDeviceId());
        to.setBroadcastType(config.getBroadcastDomainType());
        to.setType(config.getTrafficType());
        to.setIp(nic.getIp4Address());
        to.setNetmask(nic.getNetmask());
        to.setMac(nic.getMacAddress());
        to.setDns1(profile.getDns1());
        to.setDns2(profile.getDns2());
        if (nic.getGateway() != null) {
            to.setGateway(nic.getGateway());
        } else {
            to.setGateway(config.getGateway());
        }
        to.setDefaultNic(nic.isDefaultNic());
        to.setBroadcastUri(nic.getBroadcastUri());
        to.setIsolationuri(nic.getIsolationUri());
        if (profile != null) {
            to.setDns1(profile.getDns1());
            to.setDns2(profile.getDns2());
        }

        Integer networkRate = getNetworkRate(config.getId(), null);
        to.setNetworkRateMbps(networkRate);

        return to;
    }

    @Override
    @DB
    public Pair<NetworkGuru, NetworkVO> implementNetwork(long networkId, DeployDestination dest, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException,
    InsufficientCapacityException {
        Transaction.currentTxn();
        Pair<NetworkGuru, NetworkVO> implemented = new Pair<NetworkGuru, NetworkVO>(null, null);

        NetworkVO network = _networksDao.acquireInLockTable(networkId, _networkLockTimeout);
        if (network == null) {
            throw new ConcurrentOperationException("Unable to acquire network configuration: " + networkId);
        }

        try {
            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            Network.State state = network.getState();
            if (state == Network.State.Implemented || state == Network.State.Setup || state == Network.State.Implementing) {
                s_logger.debug("Network id=" + networkId + " is already implemented");
                implemented.set(guru, network);
                return implemented;
            }

            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Asking " + guru.getName() + " to implement " + network);
            }

            NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());

            network.setReservationId(context.getReservationId());
            network.setState(Network.State.Implementing);

            _networksDao.update(networkId, network);

            Network result = guru.implement(network, offering, dest, context);
            network.setCidr(result.getCidr());
            network.setBroadcastUri(result.getBroadcastUri());
            network.setGateway(result.getGateway());
            network.setMode(result.getMode());
            network.setPhysicalNetworkId(result.getPhysicalNetworkId());
            _networksDao.update(networkId, network);

            //implement network elements and re-apply all the network rules
            implementNetworkElementsAndResources(dest, context, network, offering); 

            network.setState(Network.State.Implemented);
            _networksDao.update(network.getId(), network);
            implemented.set(guru, network);
            return implemented;
        } finally {
            if (implemented.first() == null) {
                s_logger.debug("Cleaning up because we're unable to implement the network " + network);
                network.setState(Network.State.Shutdown);
                _networksDao.update(networkId, network);

                shutdownNetwork(networkId, context, false);
            }
            _networksDao.releaseFromLockTable(networkId);
        }
    }

    private void implementNetworkElementsAndResources(DeployDestination dest, ReservationContext context, NetworkVO network, NetworkOfferingVO offering)
            throws ConcurrentOperationException, InsufficientAddressCapacityException, ResourceUnavailableException, InsufficientCapacityException {
        // If this is a 1) guest virtual network 2) network has sourceNat service 3) network offering does not support a Shared source NAT rule,
        // associate a source NAT IP (if one isn't already associated with the network)

        boolean sharedSourceNat = false;
        if (areServicesSupportedInNetwork(network.getId(), Service.SourceNat)) {
            Map<Network.Capability, String> sourceNatCapabilities = getNetworkServiceCapabilities(network.getId(), Service.SourceNat);
            if (sourceNatCapabilities != null) {
                String supportedSourceNatTypes = sourceNatCapabilities.get(Capability.SupportedSourceNatTypes).toLowerCase();
                if (supportedSourceNatTypes.contains("zone")) {
                    sharedSourceNat = true;
                }
            }
        }

        if (network.getGuestType() == Network.GuestType.Isolated && areServicesSupportedInNetwork(network.getId(), Service.SourceNat) && !sharedSourceNat) {
            List<IPAddressVO> ips = _ipAddressDao.listByAssociatedNetwork(network.getId(), true);

            if (ips.isEmpty()) {
                s_logger.debug("Creating a source nat ip for " + network);
                Account owner = _accountMgr.getAccount(network.getAccountId());
                assignSourceNatIpAddress(owner, network, context.getCaller().getId()); 
            }
        }

        //get providers to implement
        List<Provider> providersToImplement = getNetworkProviders(network.getId());
        for (NetworkElement element : _networkElements) {
            if (providersToImplement.contains(element.getProvider())) {
                if (!isProviderEnabledInPhysicalNetwork(getPhysicalNetworkId(network), "VirtualRouter")) {
                    throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or not enabled in physical network id=" + network.getPhysicalNetworkId());
                }
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Asking " + element.getName() + " to implemenet " + network);
                }
                if (!element.implement(network, offering, dest, context)) {
                    throw new CloudRuntimeException("Failed to implement provider " + element.getProvider().getName() + " for network " + network);
                }
            }
        }

        // reapply all the firewall/staticNat/lb rules
        s_logger.debug("Reprogramming network " + network + " as a part of network implement");
        if (!reprogramNetworkRules(network.getId(), UserContext.current().getCaller(), network)) {
            s_logger.warn("Failed to re-program the network as a part of network " + network + " implement");
            throw new ResourceUnavailableException("Unable to apply network rules as a part of network " + network + " implement", DataCenter.class, network.getDataCenterId());
        }
    }

    protected void prepareElement(NetworkElement element, NetworkVO network, NicProfile profile, VirtualMachineProfile<? extends VMInstanceVO> vmProfile,
            DeployDestination dest, ReservationContext context) throws InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException {
        element.prepare(network, profile, vmProfile, dest, context);
        if (vmProfile.getType() == Type.User && element.getProvider() != null) {
            if (areServicesSupportedInNetwork(network.getId(), Service.Dhcp) &&
                    isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, element.getProvider()) &&
                    (element instanceof DhcpServiceProvider)) {
                DhcpServiceProvider sp = (DhcpServiceProvider)element;
                sp.addDhcpEntry(network, profile, vmProfile, dest, context);
            }
            if (areServicesSupportedInNetwork(network.getId(), Service.UserData) &&
                    isProviderSupportServiceInNetwork(network.getId(), Service.UserData, element.getProvider()) &&
                    (element instanceof UserDataServiceProvider)) {
                UserDataServiceProvider sp = (UserDataServiceProvider)element;
                sp.addPasswordAndUserdata(network, profile, vmProfile, dest, context);
            }
        }
    }

    @DB
    protected void updateNic(NicVO nic, long networkId, int count) {
        Transaction txn = Transaction.currentTxn();
        txn.start();
        _nicDao.update(nic.getId(), nic);

        if (nic.getVmType() == VirtualMachine.Type.User) {
            s_logger.debug("Changing active number of nics for network id=" + networkId + " on " + count);
            _networksDao.changeActiveNicsBy(networkId, count);
        }
        txn.commit();
    }

    @Override
    public void prepare(VirtualMachineProfile<? extends VMInstanceVO> vmProfile, DeployDestination dest, ReservationContext context) throws InsufficientCapacityException,
    ConcurrentOperationException, ResourceUnavailableException {
        List<NicVO> nics = _nicDao.listByVmId(vmProfile.getId());

        // we have to implement default nics first - to ensure that default network elements start up first in multiple nics
        // case)
        // (need for setting DNS on Dhcp to domR's Ip4 address)
        Collections.sort(nics, new Comparator<NicVO>() {

            @Override
            public int compare(NicVO nic1, NicVO nic2) {
                boolean isDefault1 = getNetwork(nic1.getNetworkId()).isDefault();
                boolean isDefault2 = getNetwork(nic2.getNetworkId()).isDefault();

                return (isDefault1 ^ isDefault2) ? ((isDefault1 ^ true) ? 1 : -1) : 0;
            }
        });

        for (NicVO nic : nics) {
            Pair<NetworkGuru, NetworkVO> implemented = implementNetwork(nic.getNetworkId(), dest, context);
            NetworkGuru guru = implemented.first();
            NetworkVO network = implemented.second();
            Integer networkRate = getNetworkRate(network.getId(), vmProfile.getId());
            NicProfile profile = null;
            if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start) {
                nic.setState(Nic.State.Reserving);
                nic.setReservationId(context.getReservationId());
                _nicDao.update(nic.getId(), nic);
                URI broadcastUri = nic.getBroadcastUri();
                if (broadcastUri == null) {
                    broadcastUri = network.getBroadcastUri();
                }

                URI isolationUri = nic.getIsolationUri();

                profile = new NicProfile(nic, network, broadcastUri, isolationUri, networkRate, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vmProfile.getHypervisorType(), network));
                guru.reserve(profile, network, vmProfile, dest, context);
                nic.setIp4Address(profile.getIp4Address());
                nic.setAddressFormat(profile.getFormat());
                nic.setIp6Address(profile.getIp6Address());
                nic.setMacAddress(profile.getMacAddress());
                nic.setIsolationUri(profile.getIsolationUri());
                nic.setBroadcastUri(profile.getBroadCastUri());
                nic.setReserver(guru.getName());
                nic.setState(Nic.State.Reserved);
                nic.setNetmask(profile.getNetmask());
                nic.setGateway(profile.getGateway());

                if (profile.getStrategy() != null) {
                    nic.setReservationStrategy(profile.getStrategy());
                }

                updateNic(nic, network.getId(), 1);
            } else {
                profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vmProfile.getHypervisorType(), network));
                guru.updateNicProfile(profile, network);
                nic.setState(Nic.State.Reserved);
                updateNic(nic, network.getId(), 1);
            }

            for (NetworkElement element : _networkElements) {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Asking " + element.getName() + " to prepare for " + nic);
                }
                prepareElement(element, network, profile, vmProfile, dest, context);
            }

            profile.setSecurityGroupEnabled(isSecurityGroupSupportedInNetwork(network));
            guru.updateNicProfile(profile, network);
            vmProfile.addNic(profile);
        }
    }

    @Override
    public <T extends VMInstanceVO> void prepareNicForMigration(VirtualMachineProfile<T> vm, DeployDestination dest) {
        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (NicVO nic : nics) {
            NetworkVO network = _networksDao.findById(nic.getNetworkId());
            Integer networkRate = getNetworkRate(network.getId(), vm.getId());

            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vm.getHypervisorType(), network));
            guru.updateNicProfile(profile, network);
            vm.addNic(profile);
        }
    }

    @Override
    public void release(VirtualMachineProfile<? extends VMInstanceVO> vmProfile, boolean forced) {
        List<NicVO> nics = _nicDao.listByVmId(vmProfile.getId());
        for (NicVO nic : nics) {
            NetworkVO network = _networksDao.findById(nic.getNetworkId());
            if (nic.getState() == Nic.State.Reserved || nic.getState() == Nic.State.Reserving) {
                Nic.State originalState = nic.getState();
                if (nic.getReservationStrategy() == Nic.ReservationStrategy.Start) {
                    NetworkGuru guru = _networkGurus.get(network.getGuruName());
                    nic.setState(Nic.State.Releasing);
                    _nicDao.update(nic.getId(), nic);
                    NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), null, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vmProfile.getHypervisorType(), network));
                    if (guru.release(profile, vmProfile, nic.getReservationId())) {
                        applyProfileToNicForRelease(nic, profile);
                        nic.setState(Nic.State.Allocated);
                        if (originalState == Nic.State.Reserved) {
                            updateNic(nic, network.getId(), -1);
                        } else {
                            _nicDao.update(nic.getId(), nic);
                        }
                    }
                } else {
                    nic.setState(Nic.State.Allocated);
                    updateNic(nic, network.getId(), -1);
                }
            }
        }
    }

    @Override
    public List<? extends Nic> getNics(long vmId) {
        return _nicDao.listByVmId(vmId);
    }

    @Override
    public List<NicProfile> getNicProfiles(VirtualMachine vm) {
        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        List<NicProfile> profiles = new ArrayList<NicProfile>();

        if (nics != null) {
            for (Nic nic : nics) {
                NetworkVO network = _networksDao.findById(nic.getNetworkId());
                Integer networkRate = getNetworkRate(network.getId(), vm.getId());

                NetworkGuru guru = _networkGurus.get(network.getGuruName());
                NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vm.getHypervisorType(), network));
                guru.updateNicProfile(profile, network);
                profiles.add(profile);
            }
        }
        return profiles;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_RELEASE, eventDescription = "disassociating Ip", async = true)
    public boolean disassociateIpAddress(long ipAddressId) {
        Long userId = UserContext.current().getCallerUserId();
        Account caller = UserContext.current().getCaller();

        // Verify input parameters
        IPAddressVO ipVO = _ipAddressDao.findById(ipAddressId);
        if (ipVO == null) {
            throw new InvalidParameterValueException("Unable to find ip address by id " + ipAddressId);
        }

        if (ipVO.getAllocatedTime() == null) {
            s_logger.debug("Ip Address id= " + ipAddressId + " is not allocated, so do nothing.");
            return true;
        }

        //verify permissions
        if (ipVO.getAllocatedToAccountId() != null) {
            _accountMgr.checkAccess(caller, null, ipVO);
        }

        Network associatedNetwork = getNetwork(ipVO.getAssociatedWithNetworkId());

        if (areServicesSupportedInNetwork(associatedNetwork.getId(), Service.SourceNat)) {
            throw new IllegalArgumentException("ip address is used for source nat purposes and can not be disassociated.");
        }

        VlanVO vlan = _vlanDao.findById(ipVO.getVlanId());
        if (!vlan.getVlanType().equals(VlanType.VirtualNetwork)) {
            throw new IllegalArgumentException("only ip addresses that belong to a virtual network may be disassociated.");
        }

        // Check for account wide pool. It will have an entry for account_vlan_map.
        if (_accountVlanMapDao.findAccountVlanMap(ipVO.getAccountId(), ipVO.getVlanId()) != null) {
            throw new InvalidParameterValueException("Ip address id=" + ipAddressId + " belongs to Account wide IP pool and cannot be disassociated");
        }

        return releasePublicIpAddress(ipAddressId, userId, caller);
    }

    @Deprecated // No one is using this method.
    public AccountVO getNetworkOwner(long networkId) {
        SearchCriteria<AccountVO> sc = AccountsUsingNetworkSearch.create();
        sc.setJoinParameters("nc", "config", networkId);
        sc.setJoinParameters("nc", "owner", true);
        List<AccountVO> accounts = _accountDao.search(sc, null);
        return accounts.size() != 0 ? accounts.get(0) : null;
    }

    @Deprecated // No one is using this method.
    public List<NetworkVO> getNetworksforOffering(long offeringId, long dataCenterId, long accountId) {
        return _networksDao.getNetworksForOffering(offeringId, dataCenterId, accountId);
    }

    @Override
    public String getNextAvailableMacAddressInNetwork(long networkId) throws InsufficientAddressCapacityException {
        String mac = _networksDao.getNextAvailableMacAddress(networkId);
        if (mac == null) {
            throw new InsufficientAddressCapacityException("Unable to create another mac address", Network.class, networkId);
        }

        return mac;
    }

    @Override
    @DB
    public Network getNetwork(long id) {
        return _networksDao.findById(id);
    }

    @Override
    public List<? extends RemoteAccessVPNServiceProvider> getRemoteAccessVpnElements() {
        List<RemoteAccessVPNServiceProvider> elements = new ArrayList<RemoteAccessVPNServiceProvider>();
        for (NetworkElement element : _networkElements) {
            if (element instanceof RemoteAccessVPNServiceProvider) {
                RemoteAccessVPNServiceProvider e = (RemoteAccessVPNServiceProvider) element;
                elements.add(e);
            }
        }

        return elements;
    }

    @Override
    public void cleanupNics(VirtualMachineProfile<? extends VMInstanceVO> vm) {
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Cleaning network for vm: " + vm.getId());
        }

        List<NicVO> nics = _nicDao.listByVmId(vm.getId());
        for (NicVO nic : nics) {
            nic.setState(Nic.State.Deallocating);
            _nicDao.update(nic.getId(), nic);
            NetworkVO network = _networksDao.findById(nic.getNetworkId());
            NicProfile profile = new NicProfile(nic, network, null, null, null, isSecurityGroupSupportedInNetwork(network), getNetworkTag(vm.getHypervisorType(), network));
            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            guru.deallocate(network, profile, vm);
            _nicDao.remove(nic.getId());
        }
    }

    @Override
    public void expungeNics(VirtualMachineProfile<? extends VMInstanceVO> vm) {
        List<NicVO> nics = _nicDao.listByVmIdIncludingRemoved(vm.getId());
        for (NicVO nic : nics) {
            _nicDao.expunge(nic.getId());
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_CREATE, eventDescription = "creating network")
    public Network createNetwork(CreateNetworkCmd cmd) throws InsufficientCapacityException, ConcurrentOperationException {
        Long networkOfferingId = cmd.getNetworkOfferingId();
        String gateway = cmd.getGateway();
        String startIP = cmd.getStartIp();
        String endIP = cmd.getEndIp();
        String netmask = cmd.getNetmask();
        String networkDomain = cmd.getNetworkDomain();
        String vlanId = cmd.getVlan();
        String name = cmd.getNetworkName();
        String displayText = cmd.getDisplayText();
        Boolean isDefault = cmd.isDefault();
        Long userId = UserContext.current().getCallerUserId();
        Account caller = UserContext.current().getCaller();
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        Long zoneId = cmd.getZoneId();
        String aclTypeStr = cmd.getAclType();
        Long domainId = cmd.getDomainId();
        boolean isDomainSpecific = false;
        Boolean subdomainAccess = cmd.getSubdomainAccess();

        // Validate network offering
        NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
        if (networkOffering == null || networkOffering.isSystemOnly()) {
            throw new InvalidParameterValueException("Unable to find network offeirng by id " + networkOfferingId);
        }
        //validate physical network and zone
        // Check if physical network exists
        PhysicalNetwork pNtwk = null;
        if (physicalNetworkId != null) {
            pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find physical network by id " + physicalNetworkId);
            }
        }

        if (zoneId == null) {
            zoneId = pNtwk.getDataCenterId();
        }

        DataCenter zone = _dcDao.findById(zoneId);
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getType())) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zone.getId());
        }

        //Only domain and account ACL types are supported in Acton.
        ACLType aclType = null;
        if (aclTypeStr != null) {
            if (aclTypeStr.equalsIgnoreCase(ACLType.Account.toString())) {
                aclType = ACLType.Account;
            } else if (aclTypeStr.equalsIgnoreCase(ACLType.Domain.toString())){
                aclType = ACLType.Domain;
            } else {
                throw new InvalidParameterValueException("Incorrect aclType specified. Check the API documentation for supported types");
            }
        } else if (zone.getNetworkType() == NetworkType.Advanced){
            aclType = ACLType.Account;
        } else if (zone.getNetworkType() == NetworkType.Basic){
            aclType = ACLType.Domain;
        }

        // Check if the network is domain specific
        if (aclType == ACLType.Domain) {
            //only Admin can create domain with aclType=Domain
            if (!_accountMgr.isAdmin(caller.getType())) {
                throw new PermissionDeniedException("Only admin can create networks with aclType=Domain");
            }

            //only shared networks can be Domain specific
            if (networkOffering.getGuestType() != GuestType.Shared) {
                throw new InvalidParameterValueException("Only " + GuestType.Shared + " networks can have aclType=" + ACLType.Domain);
            }

            if (domainId != null) {
                if (networkOffering.getTrafficType() != TrafficType.Guest || networkOffering.getGuestType() != Network.GuestType.Shared) {
                    throw new InvalidParameterValueException("Domain level networks are supported just for traffic type " + TrafficType.Guest + " and guest type " + Network.GuestType.Shared);
                } 

                DomainVO domain = _domainDao.findById(domainId);
                if (domain == null) {
                    throw new InvalidParameterValueException("Unable to find domain by id " + domainId);
                }
                _accountMgr.checkAccess(caller, domain);
            }
            isDomainSpecific = true;
        } else if (subdomainAccess != null) {
            throw new InvalidParameterValueException("Parameter subDomainAccess can be specified only with aclType=Domain");
        }
        Account owner = null;
        if (cmd.getAccountName() != null && domainId != null) {
            owner = _accountMgr.finalizeOwner(caller, cmd.getAccountName(), domainId, cmd.getProjectId());
        } else {
            owner = caller;
        }

        UserContext.current().setAccountId(owner.getAccountId());

        // if end ip is not specified, default it to startIp
        if (endIP == null && startIP != null) {
            endIP = startIP;
        }

        // If one of the following parameters are defined (starIP/endIP/netmask/gateway), all the rest should be defined too
        ArrayList<String> networkConfigs = new ArrayList<String>();
        networkConfigs.add(gateway);
        networkConfigs.add(startIP);
        networkConfigs.add(endIP);
        networkConfigs.add(netmask);
        boolean defineNetworkConfig = false;
        short configElementsCount = 0;

        for (String networkConfig : networkConfigs) {
            if (networkConfig != null) {
                configElementsCount++;
            }
        }

        if (configElementsCount > 0 && configElementsCount != networkConfigs.size()) {
            throw new InvalidParameterValueException("startIP/endIP/netmask/gateway must be specified together");
        } else if (configElementsCount == networkConfigs.size()) {
            defineNetworkConfig = true;
        }

        String cidr = null;
        if (gateway != null && netmask != null) {
            cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
        }

        // Regular user can create Guest Isolated Source Nat enabled network only
        if (caller.getType() == Account.ACCOUNT_TYPE_NORMAL && (networkOffering.getTrafficType() != TrafficType.Guest || networkOffering.getGuestType() != Network.GuestType.Isolated && areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat))) {
            throw new InvalidParameterValueException("Regular user can create a network only from the network offering having traffic type " + TrafficType.Guest + " and network type "
                    + Network.GuestType.Isolated + " with a service " + Service.SourceNat.getName() + " enabled");
        }

        // Don't allow to specify cidr if the caller is a regular user
        if (caller.getType() == Account.ACCOUNT_TYPE_NORMAL && (cidr != null || vlanId != null)) {
            throw new InvalidParameterValueException("Regular user is not allowed to specify gateway/netmask/ipRange/vlanId");
        }

        // For non-root admins check cidr limit - if it's allowed by global config value
        if (caller.getType() != Account.ACCOUNT_TYPE_ADMIN && cidr != null) {

            String[] cidrPair = cidr.split("\\/");
            int cidrSize = Integer.valueOf(cidrPair[1]);

            if (cidrSize < _cidrLimit) {
                throw new InvalidParameterValueException("Cidr size can't be less than " + _cidrLimit);
            }
        }

        Transaction txn = Transaction.currentTxn();
        txn.start();

        Long sharedDomainId = null;
        if (isDomainSpecific) {
            if (domainId != null) {
                sharedDomainId = domainId;
            } else {
                sharedDomainId = _domainMgr.getDomain(Domain.ROOT_DOMAIN).getId();
            }
        }
        
        //default owner to system if network has aclType=Domain
        if (aclType == ACLType.Domain) {
        	owner = _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM);
        }

        Network network = createGuestNetwork(networkOfferingId, name, displayText, isDefault, gateway, cidr, vlanId, networkDomain, owner, false, sharedDomainId, pNtwk, zoneId, aclType, subdomainAccess);
        //Vlan is created in 2 cases - works in Advance zone only:
        //1) GuestType is Shared
        //2) GuestType is Isolated, but SourceNat service is disabled
        boolean createVlan = ((network.getGuestType() == Network.GuestType.Shared) || (network.getGuestType() == GuestType.Isolated && !areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat)));

        if (zone.getNetworkType() == NetworkType.Advanced && caller.getType() == Account.ACCOUNT_TYPE_ADMIN && createVlan && defineNetworkConfig) {
            // Create vlan ip range
            _configMgr.createVlanAndPublicIpRange(userId, pNtwk.getDataCenterId(), null, startIP, endIP, gateway, netmask, false, vlanId, null, network.getId(), physicalNetworkId);
        }

        txn.commit();

        return network;
    }

    @Override
    @DB
    public Network createGuestNetwork(long networkOfferingId, String name, String displayText, Boolean isDefault, String gateway, String cidr, String vlanId, String networkDomain, Account owner,
            boolean isSecurityGroupEnabled, Long domainId, PhysicalNetwork pNtwk, long zoneId, ACLType aclType, Boolean subdomainAccess) throws ConcurrentOperationException, InsufficientCapacityException {

        NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
        //this method supports only guest network creation
        if (networkOffering.getTrafficType() != TrafficType.Guest) {
            s_logger.warn("Only guest networks can be created using this method");
            return null;
        }

        //Validate network offering
        if (networkOffering.getState() != NetworkOffering.State.Enabled) {
            throw new InvalidParameterValueException("Can't use network offering id=" + networkOfferingId + " as its state is not " + NetworkOffering.State.Enabled);
        }

        //Validate physical network
        if (pNtwk.getState() != PhysicalNetwork.State.Enabled) {
            throw new InvalidParameterValueException("Physical network id " + pNtwk.getId() + " is in incorrect state: " + pNtwk.getState());
        }

        //Validate zone
        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone.getNetworkType() == NetworkType.Basic) {
            //Only one guest network is supported in Basic zone
            List<NetworkVO> guestNetworks = _networksDao.listByZoneAndTrafficType(zone.getId(), TrafficType.Guest);
            if (!guestNetworks.isEmpty()) {
                throw new InvalidParameterValueException("Can't have more than one Guest network in zone with network type " + NetworkType.Basic);
            }

            //if zone is basic, only Shared network offerings w/o source nat service are allowed
            if (!(networkOffering.getGuestType() == GuestType.Shared && !areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat))) {
                throw new InvalidParameterValueException("For zone of type " + NetworkType.Basic + " only offerings of guestType " + GuestType.Shared + " with disabled " + Service.SourceNat.getName() + " service are allowed");
            }

            //In Basic zone the network should have aclType=Domain, domainId=1, subdomainAccess=true
            if (aclType == null || aclType != ACLType.Domain) {
                throw new InvalidParameterValueException("Only AclType=Domain can be specified for network creation in Basic zone");
            }

            if (domainId == null || domainId != Domain.ROOT_DOMAIN) {
                throw new InvalidParameterValueException("Guest network in Basic zone should be dedicated to ROOT domain");
            }

            if (subdomainAccess == null) {
                subdomainAccess = true;
            } else if (!subdomainAccess) {
                throw new InvalidParameterValueException("Subdomain access should be set to true for the guest network in the Basic zone");
            }

            if (vlanId == null) {
                vlanId = Vlan.UNTAGGED;
            } else {
                if (!vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
                    throw new InvalidParameterValueException("Only vlan " + Vlan.UNTAGGED + " can be created in the zone of type " + NetworkType.Basic);
                }
            }

        } else if (zone.getNetworkType() == NetworkType.Advanced) {
            if (zone.isSecurityGroupEnabled()) {
                //Only Account specific Isolated network with sourceNat service disabled are allowed in security group enabled zone
                boolean allowCreation = (networkOffering.getGuestType() == GuestType.Isolated && !areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat));
                if (!allowCreation) {
                    throw new InvalidParameterValueException("Only Account specific Isolated network with sourceNat service disabled are allowed in security group enabled zone");
                }
            }
        }

        // allow isDefault to be set only for Shared network and Isolated networks with source nat disabled service
        boolean allowSettingDefault = (zone.getNetworkType() == NetworkType.Advanced && (networkOffering.getGuestType() == GuestType.Shared || (networkOffering.getGuestType() == GuestType.Isolated && !areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat))));
        if (allowSettingDefault) {
            if (isDefault == null) {
                isDefault = false;
            }
        } else {
            if (zone.getNetworkType() == NetworkType.Basic || isDefault == null) {
                isDefault = true;
            } else {
                throw new InvalidParameterValueException("isDefault parameter can be passed in only for network creation of guestType Shared or Isolated with source nat service disabled; and only in Advance zone");
            }
        }

        // VlanId can be specified only when network offering supports it
        if (vlanId != null && !networkOffering.getSpecifyVlan()) {
            throw new InvalidParameterValueException("Can't specify vlan because network offering doesn't support it");
        }

        // Don't allow to create network with vlan that already exists in the system
        if (vlanId != null) {
            String uri = "vlan://" + vlanId;
            List<NetworkVO> networks = _networksDao.listBy(zoneId, uri);
            if ((networks != null && !networks.isEmpty())) {
                throw new InvalidParameterValueException("Network with vlan " + vlanId + " already exists in zone " + zoneId);
            }
        }
        // If networkDomain is not specified, take it from the global configuration
        if (areServicesSupportedByNetworkOffering(networkOfferingId, Service.Dns)) {
            Map<Network.Capability, String> dnsCapabilities = getNetworkOfferingServiceCapabilities(_configMgr.getNetworkOffering(networkOfferingId), Service.Dns);
            String isUpdateDnsSupported = dnsCapabilities.get(Capability.AllowDnsSuffixModification);
            if (isUpdateDnsSupported == null || !Boolean.valueOf(isUpdateDnsSupported)) {
                if (networkDomain != null) {
                    throw new InvalidParameterValueException("Domain name change is not supported by network offering id=" + networkOfferingId + " in zone id=" + zoneId);
                }
            } else {
                if (networkDomain == null) {
                    //1) Get networkDomain from the corresponding account/domain/zone
                	if (aclType == ACLType.Domain) {
                        networkDomain = getDomainNetworkDomain(domainId, zoneId);
                    } else if (aclType == ACLType.Account){
                        networkDomain = getAccountNetworkDomain(owner.getId(), zoneId);
                    } 

                    //2) If null, generate networkDomain using domain suffix from the global config variables
                    if (networkDomain == null) {
                        networkDomain = "cs" + Long.toHexString(owner.getId()) + _networkDomain;
                    }

                } else {
                    // validate network domain
                    if (!NetUtils.verifyDomainName(networkDomain)) {
                        throw new InvalidParameterValueException(
                                "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                        + "and the hyphen ('-'); can't start or end with \"-\"");
                    }
                }
            }
        }

        // In Advance zone Cidr for Shared networks and Isolated networks w/o source nat service can't be NULL - 2.2.x limitation, remove after we introduce support for multiple ip ranges
        // with different Cidrs for the same Shared network
        boolean cidrRequired = zone.getNetworkType() == NetworkType.Advanced && networkOffering.getTrafficType() == TrafficType.Guest && (networkOffering.getGuestType() == GuestType.Shared || (networkOffering.getGuestType() == GuestType.Isolated && !areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat)));
        if (cidr == null && cidrRequired) {
            throw new InvalidParameterValueException("StartIp/endIp/gateway/netmask are required when create network of type " + Network.GuestType.Shared + " and network of type " + GuestType.Isolated + " with service " + Service.SourceNat.getName() + " disabled");
        } 

        //No cidr can be specified in Basic zone
        if (zone.getNetworkType() == NetworkType.Basic && cidr != null) {
            throw new InvalidParameterValueException("StartIp/endIp/gateway/netmask can't be specified for zone of type " + NetworkType.Basic);
        }

        // Check if cidr is RFC1918 compliant if the network is Guest Isolated
        if (cidr != null && networkOffering.getGuestType() == Network.GuestType.Isolated && networkOffering.getTrafficType() == TrafficType.Guest) {
            if (!NetUtils.validateGuestCidr(cidr)) {
                throw new InvalidParameterValueException("Virtual Guest Cidr " + cidr + " is not RFC1918 compliant");
            }
        }

        Transaction txn = Transaction.currentTxn();
        txn.start();

        Long physicalNetworkId = null;
        if (pNtwk != null) {
            physicalNetworkId = pNtwk.getId();
        }
        DataCenterDeployment plan = new DataCenterDeployment(zoneId, null, null, null, null, physicalNetworkId);
        NetworkVO userNetwork = new NetworkVO();
        userNetwork.setNetworkDomain(networkDomain);

        if (cidr != null && gateway != null) {
            userNetwork.setCidr(cidr);
            userNetwork.setGateway(gateway);
            if (vlanId != null) {
                userNetwork.setBroadcastUri(URI.create("vlan://" + vlanId));
                userNetwork.setBroadcastDomainType(BroadcastDomainType.Vlan);
                if (!vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
                    userNetwork.setBroadcastDomainType(BroadcastDomainType.Vlan);
                } else {
                    userNetwork.setBroadcastDomainType(BroadcastDomainType.Native);
                }
            }
        }

        List<NetworkVO> networks = setupNetwork(owner, networkOffering, userNetwork, plan, name, displayText, isDefault, true, domainId, aclType, subdomainAccess);

        Network network = null;
        if (networks == null || networks.isEmpty()) {
            throw new CloudRuntimeException("Fail to create a network");
        } else {
            if (networks.size() > 0 && networks.get(0).getGuestType()== Network.GuestType.Isolated && networks.get(0).getTrafficType() == TrafficType.Guest) {
                Network defaultGuestNetwork = networks.get(0);
                for (Network nw : networks) {
                    if (nw.getCidr() != null && nw.getCidr().equals(zone.getGuestNetworkCidr())) {
                        defaultGuestNetwork = nw;
                    }
                }
                network = defaultGuestNetwork;
            } else {
                network = networks.get(0);
            }
        }

        txn.commit();
        UserContext.current().setEventDetails("Network Id: " + network.getId());
        return network;
    }

    @Override
    public List<? extends Network> searchForNetworks(ListNetworksCmd cmd) {
        Long id = cmd.getId();
        String keyword = cmd.getKeyword();
        Long zoneId = cmd.getZoneId();
        Account caller = UserContext.current().getCaller();
        Long domainId = cmd.getDomainId();
        String accountName = cmd.getAccountName();
        String guestIpType = cmd.getGuestIpType();
        String trafficType = cmd.getTrafficType();
        Boolean isSystem = cmd.getIsSystem();
        String aclType = cmd.getAclType();
        Boolean isDefault = cmd.isDefault();
        Long projectId = cmd.getProjectId();
        List<Long> permittedAccounts = new ArrayList<Long>();
        String path = null;
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        List<String> supportedServicesStr = cmd.getSupportedServices();

        //1) default is system to false if not specified
        //2) reset parameter to false if it's specified by the regular user
        if ((isSystem == null || caller.getType() == Account.ACCOUNT_TYPE_NORMAL) && id == null) {
            isSystem = false;
        }

        // Account/domainId parameters and isSystem are mutually exclusive
        if (isSystem != null && isSystem && (accountName != null || domainId != null)) {
            throw new InvalidParameterValueException("System network belongs to system, account and domainId parameters can't be specified");
        }

        if (domainId != null) {
            DomainVO domain = _domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Domain id=" + domainId + " doesn't exist in the system");
            }

            _accountMgr.checkAccess(caller, domain);
            if (accountName != null) {
                Account owner = _accountMgr.getActiveAccountByName(accountName, domainId);
                if (owner == null) {
                    throw new InvalidParameterValueException("Unable to find account " + accountName + " in domain " + domainId);
                }

                _accountMgr.checkAccess(caller, null, owner);
                permittedAccounts.add(owner.getId());
            }
        }

        if (!_accountMgr.isAdmin(caller.getType())) {
            permittedAccounts.add(caller.getId());
            domainId = caller.getDomainId();
        }

        //set project information
        if (projectId != null) {
            permittedAccounts.clear();
            Project project = _projectMgr.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by id " + projectId);
            }
            if (!_projectMgr.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                throw new InvalidParameterValueException("Account " + caller + " can't access project id=" + projectId);
            }
            permittedAccounts.add(project.getProjectAccountId());
        } else if (caller.getType() == Account.ACCOUNT_TYPE_NORMAL){
            permittedAccounts.addAll(_projectMgr.listPermittedProjectAccounts(caller.getId()));
        } 

        path = _domainDao.findById(caller.getDomainId()).getPath();

        Filter searchFilter = new Filter(NetworkVO.class, "id", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<NetworkVO> sb = _networksDao.createSearchBuilder();

        // Don't display networks created of system network offerings
        SearchBuilder<NetworkOfferingVO> networkOfferingSearch = _networkOfferingDao.createSearchBuilder();
        networkOfferingSearch.and("systemOnly", networkOfferingSearch.entity().isSystemOnly(), SearchCriteria.Op.EQ);
        if (isSystem != null && isSystem) {
            networkOfferingSearch.and("trafficType", networkOfferingSearch.entity().getTrafficType(), SearchCriteria.Op.EQ);
        }
        sb.join("networkOfferingSearch", networkOfferingSearch, sb.entity().getNetworkOfferingId(), networkOfferingSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        SearchBuilder<DataCenterVO> zoneSearch = _dcDao.createSearchBuilder();
        zoneSearch.and("networkType", zoneSearch.entity().getNetworkType(), SearchCriteria.Op.EQ);
        sb.join("zoneSearch", zoneSearch, sb.entity().getDataCenterId(), zoneSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        sb.and("removed", sb.entity().getRemoved(), Op.NULL);

        if (permittedAccounts.isEmpty()) {
            SearchBuilder<DomainVO> domainSearch = _domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            sb.join("domainSearch", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        List<NetworkVO> networksToReturn = new ArrayList<NetworkVO>();

        if (isSystem == null || !isSystem) {
            //Get domain level networks
            if (domainId != null) {
                networksToReturn.addAll(listDomainLevelNetworks(buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, isDefault, trafficType, physicalNetworkId, aclType), searchFilter, domainId));
            } else if (permittedAccounts.isEmpty()){
                networksToReturn.addAll(listAccountSpecificNetworksByDomainPath(buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, isDefault, trafficType, physicalNetworkId, aclType), searchFilter, path));
            }

            //get account specific networks
            if (!permittedAccounts.isEmpty()){
                networksToReturn.addAll(listAccountSpecificNetworks(buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, isDefault, trafficType, physicalNetworkId, aclType), searchFilter, permittedAccounts));
            }
        } else {
            networksToReturn = _networksDao.search(buildNetworkSearchCriteria(sb, keyword, id, isSystem, zoneId, guestIpType, isDefault, trafficType, physicalNetworkId, null), searchFilter);
        }

        if (supportedServicesStr != null && !supportedServicesStr.isEmpty() && !networksToReturn.isEmpty()) {
            List<NetworkVO> supportedNetworks = new ArrayList<NetworkVO>();
            Service[] suppportedServices = new Service[supportedServicesStr.size()];
            int i = 0;
            for (String supportedServiceStr : supportedServicesStr) {
                Service service = Service.getService(supportedServiceStr);
                if (service == null) {
                    throw new InvalidParameterValueException("Invalid service specified " + supportedServiceStr);
                } else {
                    suppportedServices[i] = service;
                }
                i++;
            }

            for (NetworkVO network : networksToReturn) {
                if (areServicesSupportedInNetwork(network.getId(), suppportedServices)) {
                    supportedNetworks.add(network);
                }
            }

            return supportedNetworks;
        } else {
            return networksToReturn;
        }
    }

    private SearchCriteria<NetworkVO> buildNetworkSearchCriteria(SearchBuilder<NetworkVO> sb, String keyword, Long id, Boolean isSystem, Long zoneId, String guestIpType, Boolean isDefault, String trafficType, Long physicalNetworkId, String aclType) {
        SearchCriteria<NetworkVO> sc = sb.create();

        if (isSystem != null) {
            sc.setJoinParameters("networkOfferingSearch", "systemOnly", isSystem);
        }

        if (keyword != null) {
            SearchCriteria<NetworkVO> ssc = _networksDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }

        if (guestIpType != null) {
            sc.addAnd("guestType", SearchCriteria.Op.EQ, guestIpType);
        }

        if (isDefault != null) {
            sc.addAnd("isDefault", SearchCriteria.Op.EQ, isDefault);
        }

        if (trafficType != null) {
            sc.addAnd("trafficType", SearchCriteria.Op.EQ, trafficType);
        }

        if (aclType != null) {
            sc.addAnd("aclType", SearchCriteria.Op.EQ, aclType.toString());
        }

        if (physicalNetworkId != null) {
            sc.addAnd("physicalNetworkId", SearchCriteria.Op.EQ, physicalNetworkId);
        }

        return sc;
    }


    private List<NetworkVO> listDomainLevelNetworks(SearchCriteria<NetworkVO> sc, Filter searchFilter, long domainId) {
        List<Long> networkIds = new ArrayList<Long>();
        Set<Long> allowedDomains = _domainMgr.getDomainParentIds(domainId);
        List<NetworkDomainVO> maps = _networkDomainDao.listDomainNetworkMapByDomain(allowedDomains.toArray());

        for (NetworkDomainVO map : maps) {
            boolean subdomainAccess = (map.isSubdomainAccess() != null) ? map.isSubdomainAccess() : getAllowSubdomainAccessGlobal();
            if (map.getDomainId() == domainId || subdomainAccess) {
                networkIds.add(map.getNetworkId());
            }
        }

        if (!networkIds.isEmpty()) {
            SearchCriteria<NetworkVO> domainSC = _networksDao.createSearchCriteria();
            domainSC.addAnd("id", SearchCriteria.Op.IN, networkIds.toArray());
            domainSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Domain.toString());

            sc.addAnd("id", SearchCriteria.Op.SC, domainSC);
            return _networksDao.search(sc, searchFilter);
        } else {
            return new ArrayList<NetworkVO>();
        }
    }

    private List<NetworkVO> listAccountSpecificNetworks(SearchCriteria<NetworkVO> sc, Filter searchFilter, List<Long> permittedAccounts) {
        SearchCriteria<NetworkVO> accountSC = _networksDao.createSearchCriteria();
        if (!permittedAccounts.isEmpty()) {
            accountSC.addAnd("accountId", SearchCriteria.Op.IN, permittedAccounts.toArray());
        }

        accountSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Account.toString());

        sc.addAnd("id", SearchCriteria.Op.SC, accountSC);
        return _networksDao.search(sc, searchFilter);
    }

    private List<NetworkVO> listAccountSpecificNetworksByDomainPath(SearchCriteria<NetworkVO> sc, Filter searchFilter, String path) {
        SearchCriteria<NetworkVO> accountSC = _networksDao.createSearchCriteria();
        accountSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Account.toString());

        if (path != null) {
            sc.setJoinParameters("domainSearch", "path", path + "%");
        }

        return _networksDao.search(sc, searchFilter);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_DELETE, eventDescription = "deleting network", async = true)
    public boolean deleteNetwork(long networkId) {

        Account caller = UserContext.current().getCaller();

        // Verify network id
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("unable to find network " + networkId);
        }

        //don't allow to delete system network
        if (isNetworkSystem(network)) {
            throw new InvalidParameterValueException("Network " + network + " is system and can't be removed");
        }

        Account owner = _accountMgr.getAccount(network.getAccountId());

        // Perform permission check
        _accountMgr.checkAccess(caller, null, network);

        User callerUser = _accountMgr.getActiveUser(UserContext.current().getCallerUserId());
        ReservationContext context = new ReservationContextImpl(null, null, callerUser, owner);

        return destroyNetwork(networkId, context);
    }

    @Override
    @DB
    public void shutdownNetwork(long networkId, ReservationContext context, boolean cleanupElements) {

        Transaction txn = Transaction.currentTxn();
        txn.start();
        NetworkVO network = _networksDao.lockRow(networkId, true);
        if (network == null) {
            s_logger.debug("Unable to find network with id: " + networkId);
            return;
        }
        if (network.getState() != Network.State.Implemented && network.getState() != Network.State.Shutdown) {
            s_logger.debug("Network is not implemented: " + network);
            return;
        }

        network.setState(Network.State.Shutdown);
        _networksDao.update(network.getId(), network);
        txn.commit();

        boolean success = shutdownNetworkElementsAndResources(context, cleanupElements, network);

        txn.start();
        if (success) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Network id=" + networkId + " is shutdown successfully, cleaning up corresponding resources now.");
            }
            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            NetworkProfile profile = convertNetworkToNetworkProfile(network.getId());
            guru.shutdown(profile, _networkOfferingDao.findById(network.getNetworkOfferingId()));

            applyProfileToNetwork(network, profile);

            network.setState(Network.State.Allocated);
            _networksDao.update(network.getId(), network);
            _networksDao.clearCheckForGc(networkId);

        } else {
            network.setState(Network.State.Implemented);
            _networksDao.update(network.getId(), network);
        }
        txn.commit();
    }

    private boolean shutdownNetworkElementsAndResources(ReservationContext context, boolean cleanupElements, NetworkVO network) {
        //1) Cleanup all the rules for the network. If it fails, just log the failure and proceed with shutting down the elements
        boolean cleanupResult = true;
        try {
            cleanupResult = shutdownNetworkResources(network.getId(), context.getAccount(), context.getCaller().getId());
        } catch (Exception ex) {
            s_logger.warn("shutdownNetworkRules failed during the network " + network + " shutdown due to ", ex);
        } finally {
            //just warn the administrator that the network elements failed to shutdown
            if (!cleanupResult) {
                s_logger.warn("Failed to cleanup network id=" + network.getId() + " resources as a part of shutdownNetwork");
            }
        }  

        //2) Shutdown all the network elements
        //get providers to shutdown
        List<Provider> providersToShutdown = getNetworkProviders(network.getId());
        boolean success = true;
        for (NetworkElement element : _networkElements) {
            if (providersToShutdown.contains(element.getProvider())) {
                try {
                    if (!isProviderEnabledInPhysicalNetwork(getPhysicalNetworkId(network), "VirtualRouter")) {
                        s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName() + " either doesn't exist or not enabled in the physical network " + getPhysicalNetworkId(network));
                        success = false;
                    }
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Sending network shutdown to " + element.getName());
                    }
                    if (!element.shutdown(network, context, cleanupElements)) {
                        s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName());
                        success = false;
                    }
                } catch (ResourceUnavailableException e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                } catch (ConcurrentOperationException e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                } catch (Exception e) {
                    s_logger.warn("Unable to complete shutdown of the network elements due to element: " + element.getName(), e);
                    success = false;
                }
            }
        }
        return success;
    }

    @Override
    @DB
    public boolean destroyNetwork(long networkId, ReservationContext context) {
        Account callerAccount = _accountMgr.getAccount(context.getCaller().getAccountId());

        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            s_logger.debug("Unable to find network with id: " + networkId);
            return false;
        }

        // Don't allow to delete network via api call when it has vms assigned to it
        int nicCount = getActiveNicsInNetwork(networkId);
        if (nicCount > 0) {
            s_logger.debug("Unable to remove the network id=" + networkId + " as it has active Nics.");
            return false;
        }

        // Make sure that there are no user vms in the network that are not Expunged/Error
        List<UserVmVO> userVms = _userVmDao.listByNetworkIdAndStates(networkId);

        for (UserVmVO vm : userVms) {
            if (!(vm.getState() == VirtualMachine.State.Error || (vm.getState() == VirtualMachine.State.Expunging && vm.getRemoved() != null))) {
                s_logger.warn("Can't delete the network, not all user vms are expunged. Vm " + vm + " is in " + vm.getState() + " state");
                return false;
            }
        }

        // Shutdown network first
        shutdownNetwork(networkId, context, false);

        // get updated state for the network
        network = _networksDao.findById(networkId);
        if (network.getState() != Network.State.Allocated && network.getState() != Network.State.Setup) {
            s_logger.debug("Network is not not in the correct state to be destroyed: " + network.getState());
            return false;
        }

        boolean success = true;
        if (!cleanupNetworkResources(networkId, callerAccount, context.getCaller().getId())) {
            s_logger.warn("Unable to delete network id=" + networkId + ": failed to cleanup network resources");
            return false;
        }

        //get providers to destroy
        List<Provider> providersToDestroy = getNetworkProviders(network.getId());
        for (NetworkElement element : _networkElements) {
            if (providersToDestroy.contains(element.getProvider())) {
                try {
                    if (!isProviderEnabledInPhysicalNetwork(getPhysicalNetworkId(network), "VirtualRouter")) {
                        s_logger.warn("Unable to complete destroy of the network elements due to element: " + element.getName() + " either doesn't exist or not enabled in the physical network " + getPhysicalNetworkId(network));
                        success = false;
                    }

                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Sending destroy to " + element);
                    }

                    element.destroy(network);
                } catch (ResourceUnavailableException e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                } catch (ConcurrentOperationException e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                } catch (Exception e) {
                    s_logger.warn("Unable to complete destroy of the network due to element: " + element.getName(), e);
                    success = false;
                }
            }
        }

        if (success) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Network id=" + networkId + " is destroyed successfully, cleaning up corresponding resources now.");
            }
            NetworkGuru guru = _networkGurus.get(network.getGuruName());
            Account owner = _accountMgr.getAccount(network.getAccountId());

            Transaction txn = Transaction.currentTxn();
            txn.start();
            guru.trash(network, _networkOfferingDao.findById(network.getNetworkOfferingId()), owner);

            if (!deleteVlansInNetwork(network.getId(), context.getCaller().getId())) {
                success = false;
                s_logger.warn("Failed to delete network " + network + "; was unable to cleanup corresponding ip ranges");
            } else {
                // commit transaction only when ips and vlans for the network are released successfully
                network.setState(Network.State.Destroy);
                _networksDao.update(network.getId(), network);
                _networksDao.remove(network.getId());
                txn.commit();
            }
        }

        return success;
    }

    private boolean deleteVlansInNetwork(long networkId, long userId) {
        List<VlanVO> vlans = _vlanDao.listVlansByNetworkId(networkId);
        boolean result = true;
        for (VlanVO vlan : vlans) {
            if (!_configMgr.deleteVlanAndPublicIpRange(_accountMgr.getSystemUser().getId(), vlan.getId())) {
                s_logger.warn("Failed to delete vlan " + vlan.getId() + ");");
                result = false;
            }
        }
        return result;
    }

    @Override
    /* The rules here is only the same kind of rule, e.g. all load balancing rules or all port forwarding rules */
    public boolean applyRules(List<? extends FirewallRule> rules, boolean continueOnError) throws ResourceUnavailableException {
        if (rules == null || rules.size() == 0) {
            s_logger.debug("There are no rules to forward to the network elements");
            return true;
        }

        boolean success = true;
        Network network = _networksDao.findById(rules.get(0).getNetworkId());
        Purpose purpose = rules.get(0).getPurpose();
        for (NetworkElement ne : _networkElements) {
            try {
                boolean handled;
                switch (purpose) {
                case LoadBalancing:
                    if (!(ne instanceof LoadBalancingServiceProvider)) {
                        continue;
                    }
                    handled = ((LoadBalancingServiceProvider)ne).applyLBRules(network, (List<LoadBalancingRule>)rules);
                    break;
                case PortForwarding:
                    if (!(ne instanceof PortForwardingServiceProvider)) {
                        continue;
                    }
                    handled = ((PortForwardingServiceProvider)ne).applyPFRules(network, (List<PortForwardingRule>)rules);
                    break;
                case StaticNat:
                    /* It's firewall rule for static nat, not static nat rule */
                    /* Fall through */
                case Firewall: 
                    if (!(ne instanceof FirewallServiceProvider)) {
                        continue;
                    }
                    handled = ((FirewallServiceProvider)ne).applyFWRules(network, rules);
                    break;
                default:
                    s_logger.debug("Unable to handle network rules for purpose: " + purpose.toString());
                    handled = false;
                }
                s_logger.debug("Network Rules for network " + network.getId() + " were " + (handled ? "" : " not") + " handled by " + ne.getName());
            } catch (ResourceUnavailableException e) {
                if (!continueOnError) {
                    throw e;
                }
                s_logger.warn("Problems with " + ne.getName() + " but pushing on", e);
                success = false;
            }
        }

        return success;
    }

    public class NetworkGarbageCollector implements Runnable {

        @Override
        public void run() {
            try {
                List<Long> shutdownList = new ArrayList<Long>();
                long currentTime = System.currentTimeMillis() >> 10;
                HashMap<Long, Long> stillFree = new HashMap<Long, Long>();

                List<Long> networkIds = _networksDao.findNetworksToGarbageCollect();
                for (Long networkId : networkIds) {
                    Long time = _lastNetworkIdsToFree.remove(networkId);
                    if (time == null) {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("We found network " + networkId + " to be free for the first time.  Adding it to the list: " + currentTime);
                        }
                        stillFree.put(networkId, currentTime);
                    } else if (time > (currentTime - _networkGcWait)) {
                        if (s_logger.isDebugEnabled()) {
                            s_logger.debug("Network " + networkId + " is still free but it's not time to shutdown yet: " + time);
                        }
                        stillFree.put(networkId, time);
                    } else {
                        shutdownList.add(networkId);
                    }
                }

                _lastNetworkIdsToFree = stillFree;

                for (Long networkId : shutdownList) {

                    // If network is removed, unset gc flag for it
                    if (getNetwork(networkId) == null) {
                        s_logger.debug("Network id=" + networkId + " is removed, so clearing up corresponding gc check");
                        _networksDao.clearCheckForGc(networkId);
                    } else {
                        try {

                            User caller = _accountMgr.getSystemUser();
                            Account owner = _accountMgr.getAccount(getNetwork(networkId).getAccountId());

                            ReservationContext context = new ReservationContextImpl(null, null, caller, owner);

                            shutdownNetwork(networkId, context, false);
                        } catch (Exception e) {
                            s_logger.warn("Unable to shutdown network: " + networkId);
                        }
                    }
                }
            } catch (Exception e) {
                s_logger.warn("Caught exception while running network gc: ", e);
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_RESTART, eventDescription = "restarting network", async = true)
    public boolean restartNetwork(RestartNetworkCmd cmd, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        // This method restarts all network elements belonging to the network and re-applies all the rules
        Long networkId = cmd.getNetworkId();

        User callerUser = _accountMgr.getActiveUser(UserContext.current().getCallerUserId());
        Account callerAccount = _accountMgr.getActiveAccountById(callerUser.getAccountId());

        // Check if network exists
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Network with id=" + networkId + " doesn't exist");
        }

        // Don't allow to restart network if it's not in Implemented/Setup state
        if (!(network.getState() == Network.State.Implemented || network.getState() == Network.State.Setup)) {
            throw new InvalidParameterValueException("Network is not in the right state to be restarted. Correct states are: " + Network.State.Implemented + ", " + Network.State.Setup);
        }

        _accountMgr.checkAccess(callerAccount, null, network);

        boolean success = restartNetwork(networkId, callerAccount, callerUser, cleanup);

        if (success) {
            s_logger.debug("Network id=" + networkId + " is restarted successfully.");
        } else {
            s_logger.warn("Network id=" + networkId + " failed to restart.");
        }

        return success;
    }

    @Override
    public boolean startNetwork(long networkId, DeployDestination dest, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        // Check if network exists
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Network with id=" + networkId + " doesn't exist");
        }

        // implement the network
        s_logger.debug("Starting network " + network + "...");
        Pair<NetworkGuru, NetworkVO> implementedNetwork = implementNetwork(networkId, dest, context);
        if (implementedNetwork.first() == null) {
            s_logger.warn("Failed to start the network " + network);
            return false;
        } else {
            return true;
        }
    }

    private boolean restartNetwork(long networkId, Account callerAccount, User callerUser, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        NetworkVO network = _networksDao.findById(networkId);

        s_logger.debug("Restarting network " + networkId + "...");

        //shutdown the network
        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        s_logger.debug("Shutting down the network id=" + networkId + " as a part of network restart");

        if (!shutdownNetworkElementsAndResources(context, cleanup, network)) {
            s_logger.debug("Failed to shutdown the network elements and resources as a part of network restart: " + network.getState());
            return false;
        }

        //implement the network elements and rules again
        DeployDestination dest = new DeployDestination(_dcDao.findById(network.getDataCenterId()), null, null, null);

        s_logger.debug("Implementing the network " + network + " elements and resources as a part of network restart");
        NetworkOfferingVO offering = _networkOfferingDao.findById(network.getNetworkOfferingId());

        try {
            implementNetworkElementsAndResources(dest, context, network, offering);
        } catch (Exception ex) {
            s_logger.warn("Failed to implement network " + network + " elements and resources as a part of network restart due to ", ex);
            return false;
        }

        return true;
    }


    //This method re-programs the rules/ips for existing network
    protected boolean reprogramNetworkRules(long networkId, Account caller, NetworkVO network) throws ResourceUnavailableException {
        boolean success = true;
        // associate all ip addresses
        if (!applyIpAssociations(network, false)) {
            s_logger.warn("Failed to apply ip addresses as a part of network id" + networkId + " restart");
            success = false;
        }

        // apply static nat
        if (!_rulesMgr.applyStaticNatsForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to apply static nats a part of network id" + networkId + " restart");
            success = false;
        }

        // apply firewall rules
        List<FirewallRuleVO> firewallRulesToApply = _firewallDao.listByNetworkAndPurpose(networkId, Purpose.Firewall);
        if (!_firewallMgr.applyFirewallRules(firewallRulesToApply, false, caller)) {
            s_logger.warn("Failed to reapply firewall rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply port forwarding rules
        if (!_rulesMgr.applyPortForwardingRulesForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to reapply port forwarding rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply static nat rules
        if (!_rulesMgr.applyStaticNatRulesForNetwork(networkId, false, caller)) {
            s_logger.warn("Failed to reapply static nat rule(s) as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply load balancer rules
        if (!_lbMgr.applyLoadBalancersForNetwork(networkId)) {
            s_logger.warn("Failed to reapply load balancer rules as a part of network id=" + networkId + " restart");
            success = false;
        }

        // apply vpn rules
        List<? extends RemoteAccessVpn> vpnsToReapply = _vpnMgr.listRemoteAccessVpns(networkId);
        if (vpnsToReapply != null) {
            for (RemoteAccessVpn vpn : vpnsToReapply) {
                // Start remote access vpn per ip
                if (_vpnMgr.startRemoteAccessVpn(vpn.getServerAddressId(), false) == null) {
                    s_logger.warn("Failed to reapply vpn rules as a part of network id=" + networkId + " restart");
                    success = false;
                }
            }
        }
        return success;
    }

    @Override
    public int getActiveNicsInNetwork(long networkId) {
        return _networksDao.getActiveNicsIn(networkId);
    }


    @Override
    public Map<Service, Map<Capability, String>> getNetworkCapabilities(long networkId) {

        Map<Service, Map<Capability, String>> networkCapabilities = new HashMap<Service, Map<Capability, String>>();

        //list all services of this networkOffering
        List<NetworkServiceMapVO> servicesMap = _ntwkSrvcDao.getServicesInNetwork(networkId);
        for(NetworkServiceMapVO instance : servicesMap ){
            Service service = Service.getService(instance.getService());
            NetworkElement element = getElementImplementingProvider(instance.getProvider());
            if(element != null){
                Map<Service, Map<Capability, String>> elementCapabilities = element.getCapabilities();;
                if (elementCapabilities != null) {
                    networkCapabilities.put(service, elementCapabilities.get(service));
                }
            }
        }

        return networkCapabilities;
    }

    @Override
    public Map<Capability, String> getNetworkServiceCapabilities(long networkId, Service service) {

        if (!areServicesSupportedInNetwork(networkId, service)) {
            throw new UnsupportedServiceException("Service " + service.getName() + " is not supported in the network id=" + networkId);
        }

        Map<Capability, String> serviceCapabilities = new HashMap<Capability, String>();

        //get the Provider for this Service for this offering
        String provider = _ntwkSrvcDao.getProviderForServiceInNetwork(networkId, service);

        NetworkElement element = getElementImplementingProvider(provider);
        if(element != null){
            Map<Service, Map<Capability, String>> elementCapabilities = element.getCapabilities();;

            if (elementCapabilities == null || elementCapabilities.get(service) == null) {
                throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the element=" + element.getName() + " implementing Provider=" + provider);
            }
            serviceCapabilities = elementCapabilities.get(service);
        }

        return serviceCapabilities;
    }

    @Override
    public Map<Capability, String> getNetworkOfferingServiceCapabilities(NetworkOffering offering, Service service) {

        if (!areServicesSupportedByNetworkOffering(offering.getId(), service)) {
            throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the network offering " + offering);
        }

        Map<Capability, String> serviceCapabilities = new HashMap<Capability, String>();

        //get the Provider for this Service for this offering
        String provider = _ntwkOfferingSrvcDao.getProviderForServiceForNetworkOffering(offering.getId(), service);

        //FIXME we return the capabilities of the first provider of the service - what if we have multiple providers for same Service?
        NetworkElement element = getElementImplementingProvider(provider);
        if(element != null){
            Map<Service, Map<Capability, String>> elementCapabilities = element.getCapabilities();;

            if (elementCapabilities == null || elementCapabilities.get(service) == null) {
                throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the element=" + element.getName() + " implementing Provider=" + provider);
            }
            serviceCapabilities = elementCapabilities.get(service);
        }

        return serviceCapabilities;
    }

    @Override
    public NetworkVO getSystemNetworkByZoneAndTrafficType(long zoneId, TrafficType trafficType) {
        // find system public network offering
        Long networkOfferingId = null;
        List<NetworkOfferingVO> offerings = _networkOfferingDao.listSystemNetworkOfferings();
        for (NetworkOfferingVO offering : offerings) {
            if (offering.getTrafficType() == trafficType) {
                networkOfferingId = offering.getId();
                break;
            }
        }

        if (networkOfferingId == null) {
            throw new InvalidParameterValueException("Unable to find system network offering with traffic type " + trafficType);
        }

        List<NetworkVO> networks = _networksDao.listBy(Account.ACCOUNT_ID_SYSTEM, networkOfferingId, zoneId);
        if (networks == null || networks.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find network with traffic type " + trafficType + " in zone " + zoneId);
        }
        return networks.get(0);
    }

    @Override
    public NetworkVO getNetworkWithSecurityGroupEnabled(Long zoneId) {
        List<NetworkVO> networks = _networksDao.listByZoneSecurityGroup(zoneId);
        if (networks == null || networks.isEmpty()) {
            return null;
        }

        if (networks.size() > 1) {
            s_logger.debug("There are multiple network with security group enabled? select one of them...");
        }
        return networks.get(0);
    }

    @Override
    public PublicIpAddress getPublicIpAddress(long ipAddressId) {
        IPAddressVO addr = _ipAddressDao.findById(ipAddressId);
        if (addr == null) {
            return null;
        }

        return new PublicIp(addr, _vlanDao.findById(addr.getVlanId()), NetUtils.createSequenceBasedMacAddress(addr.getMacAddress()));
    }

    @Override
    public List<VlanVO> listPodVlans(long podId) {
        List<VlanVO> vlans = _vlanDao.listVlansForPodByType(podId, VlanType.DirectAttached);
        return vlans;
    }

    @Override
    public List<NetworkVO> listNetworksUsedByVm(long vmId, boolean isSystem) {
        List<NetworkVO> networks = new ArrayList<NetworkVO>();

        List<NicVO> nics = _nicDao.listByVmId(vmId);
        if (nics != null) {
            for (Nic nic : nics) {
                NetworkVO network = _networksDao.findByIdIncludingRemoved(nic.getNetworkId());

                if (isNetworkSystem(network) == isSystem) {
                    networks.add(network);
                }
            }
        }

        return networks;
    }

    @Override
    public Nic getNicInNetwork(long vmId, long networkId) {
        return _nicDao.findByInstanceIdAndNetworkId(networkId, vmId);
    }

    @Override
    public String getIpInNetwork(long vmId, long networkId) {
        Nic guestNic = getNicInNetwork(vmId, networkId);
        assert (guestNic != null && guestNic.getIp4Address() != null) : "Vm doesn't belong to network associated with ipAddress or ip4 address is null";
        return guestNic.getIp4Address();
    }

    @Override
    public String getIpInNetworkIncludingRemoved(long vmId, long networkId) {
        Nic guestNic = getNicInNetworkIncludingRemoved(vmId, networkId);
        assert (guestNic != null && guestNic.getIp4Address() != null) : "Vm doesn't belong to network associated with ipAddress or ip4 address is null";
        return guestNic.getIp4Address();
    }


    private Nic getNicInNetworkIncludingRemoved(long vmId, long networkId) {
        return _nicDao.findByInstanceIdAndNetworkIdIncludingRemoved(networkId, vmId);
    }

    @Override
    @DB
    public boolean associateIpAddressListToAccount(long userId, long accountId, long zoneId, Long vlanId, Network network) throws InsufficientCapacityException, ConcurrentOperationException,
    ResourceUnavailableException {
        Account owner = _accountMgr.getActiveAccountById(accountId);
        boolean createNetwork = false;

        Transaction txn = Transaction.currentTxn();

        txn.start();

        if (network == null) {
            List<? extends Network> networks = getIsolatedNetworksOwnedByAccountInZone(zoneId, owner);
            if (networks.size() == 0) {
                createNetwork = true;
            } else {
                network = networks.get(0);
            }
        }

        // create new Virtual network for the user if it doesn't exist
        if (createNetwork) {
            List<? extends NetworkOffering> offerings = _configMgr.listNetworkOfferings(TrafficType.Guest, false);
            PhysicalNetwork physicalNetwork = translateZoneIdToPhysicalNetwork(zoneId);
            network = createGuestNetwork(offerings.get(0).getId(), owner.getAccountName() + "-network", owner.getAccountName() + "-network", null, null, null, null, null, owner, false, null, physicalNetwork, zoneId, ACLType.Account, null);

            if (network == null) {
                s_logger.warn("Failed to create default Virtual network for the account " + accountId + "in zone " + zoneId);
                return false;
            }
        }

        // Check if there is a source nat ip address for this account; if not - we have to allocate one
        boolean allocateSourceNat = false;
        List<IPAddressVO> sourceNat = _ipAddressDao.listByAssociatedNetwork(network.getId(), true);
        if (sourceNat.isEmpty()) {
            allocateSourceNat = true;
        }

        // update all ips with a network id, mark them as allocated and update resourceCount/usage
        List<IPAddressVO> ips = _ipAddressDao.listByVlanId(vlanId);
        boolean isSourceNatAllocated = false;
        for (IPAddressVO addr : ips) {
            if (addr.getState() != State.Allocated) {
                if (!isSourceNatAllocated && allocateSourceNat) {
                    addr.setSourceNat(true);
                    isSourceNatAllocated = true;
                } else {
                    addr.setSourceNat(false);
                }
                addr.setAssociatedWithNetworkId(network.getId());
                addr.setAllocatedTime(new Date());
                addr.setAllocatedInDomainId(owner.getDomainId());
                addr.setAllocatedToAccountId(owner.getId());
                addr.setState(IpAddress.State.Allocating);
                markPublicIpAsAllocated(addr);
            }
        }

        txn.commit();
        return true;
    }

    @Override
    public List<NicVO> getNicsForTraffic(long vmId, TrafficType type) {
        SearchCriteria<NicVO> sc = NicForTrafficTypeSearch.create();
        sc.setParameters("instance", vmId);
        sc.setJoinParameters("network", "traffictype", type);

        return _nicDao.search(sc, null);
    }

    @Override
    public IpAddress getIp(long ipAddressId) {
        return _ipAddressDao.findById(ipAddressId);
    }

    @Override
    public NetworkProfile convertNetworkToNetworkProfile(long networkId) {
        NetworkVO network = _networksDao.findById(networkId);
        NetworkGuru guru = _networkGurus.get(network.getGuruName());
        NetworkProfile profile = new NetworkProfile(network);
        guru.updateNetworkProfile(profile);

        return profile;
    }

    @Override
    public Network getDefaultNetworkForVm(long vmId) {
        Nic defaultNic = getDefaultNic(vmId);
        if (defaultNic == null) {
            return null;
        } else {
            return _networksDao.findById(defaultNic.getNetworkId());
        }
    }

    @Override
    public Nic getDefaultNic(long vmId) {
        List<NicVO> nics = _nicDao.listByVmId(vmId);
        Nic defaultNic = null;
        if (nics != null) {
            for (Nic nic : nics) {
                if (nic.isDefaultNic()) {
                    defaultNic = nic;
                    break;
                }
            }
        } else {
            s_logger.debug("Unable to find default network for the vm; vm doesn't have any nics");
            return null;
        }

        if (defaultNic == null) {
            s_logger.debug("Unable to find default network for the vm; vm doesn't have default nic");
        }

        return defaultNic;

    }

    @Override
    public List<? extends UserDataServiceProvider> getPasswordResetElements() {
        List<UserDataServiceProvider> elements = new ArrayList<UserDataServiceProvider>();
        for (NetworkElement element : _networkElements) {
            if (element instanceof UserDataServiceProvider) {
                UserDataServiceProvider e = (UserDataServiceProvider)element;
                elements.add(e);
            }
        }
        return elements;
    }


    @Override
    public boolean networkIsConfiguredForExternalNetworking(long zoneId, long networkId) {
        boolean netscalerInNetwork = isProviderForNetwork(Network.Provider.Netscaler, networkId);
        boolean juniperInNetwork = isProviderForNetwork(Network.Provider.JuniperSRX, networkId);
        boolean f5InNetwork = isProviderForNetwork(Network.Provider.F5BigIp, networkId);

        if (netscalerInNetwork || juniperInNetwork || f5InNetwork) {
            return true;
        } else {
            return false;
        }
    }    

    @Override
    public boolean areServicesSupportedByNetworkOffering(long networkOfferingId, Service... services) {
        return (_ntwkOfferingSrvcDao.areServicesSupportedByNetworkOffering(networkOfferingId, services));
    }

    @Override
    public boolean areServicesSupportedInNetwork(long networkId, Service... services) {
        return (_ntwkSrvcDao.areServicesSupportedInNetwork(networkId, services));
    }

    private boolean cleanupIpResources(long ipId, long userId, Account caller) {
        boolean success = true;

        //Revoke all firewall rules for the ip
        try {
            s_logger.debug("Revoking all " + Purpose.Firewall + "rules as a part of public IP id=" + ipId + " release...");
            if (!_firewallMgr.revokeFirewallRulesForIp(ipId, userId, caller)) {
                s_logger.warn("Unable to revoke all the firewall rules for ip id=" + ipId + " as a part of ip release");
                success = false;
            }
        } catch (ResourceUnavailableException e) {
            s_logger.warn("Unable to revoke all firewall rules for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        //Revoke all PF/Static nat rules for the ip
        try {
            s_logger.debug("Revoking all " + Purpose.PortForwarding + "/" + Purpose.StaticNat + " rules as a part of public IP id=" + ipId + " release...");
            if (!_rulesMgr.revokeAllPFAndStaticNatRulesForIp(ipId, userId, caller)) {
                s_logger.warn("Unable to revoke all the port forwarding rules for ip id=" + ipId + " as a part of ip release");
                success = false;
            }
        } catch (ResourceUnavailableException e) {
            s_logger.warn("Unable to revoke all the port forwarding rules for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        s_logger.debug("Revoking all " + Purpose.LoadBalancing + " rules as a part of public IP id=" + ipId + " release...");
        if (!_lbMgr.removeAllLoadBalanacersForIp(ipId, caller, userId)) {
            s_logger.warn("Unable to revoke all the load balancer rules for ip id=" + ipId + " as a part of ip release");
            success = false;
        }

        // remote access vpn can be enabled only for static nat ip, so this part should never be executed under normal
        // conditions
        // only when ip address failed to be cleaned up as a part of account destroy and was marked as Releasing, this part of
        // the code would be triggered
        s_logger.debug("Cleaning up remote access vpns as a part of public IP id=" + ipId + " release...");
        try {
            _vpnMgr.destroyRemoteAccessVpn(ipId);
        } catch (ResourceUnavailableException e) {
            s_logger.warn("Unable to destroy remote access vpn for ip id=" + ipId + " as a part of ip release", e);
            success = false;
        }

        return success;
    }

    @Override
    public String getIpOfNetworkElementInVirtualNetwork(long accountId, long dataCenterId) {

        List<NetworkVO> virtualNetworks = _networksDao.listBy(accountId, dataCenterId, Network.GuestType.Isolated);

        if (virtualNetworks.isEmpty()) {
            s_logger.trace("Unable to find default Virtual network account id=" + accountId);
            return null;
        }

        NetworkVO virtualNetwork = virtualNetworks.get(0);

        NicVO networkElementNic = _nicDao.findByNetworkIdAndType(virtualNetwork.getId(), Type.DomainRouter);

        if (networkElementNic != null) {
            return networkElementNic.getIp4Address();
        } else {
            s_logger.warn("Unable to set find network element for the network id=" + virtualNetwork.getId());
            return null;
        }
    }

    @Override
    public List<NetworkVO> listNetworksForAccount(long accountId, long zoneId, Network.GuestType type, Boolean isDefault) {
        List<NetworkVO> accountNetworks = new ArrayList<NetworkVO>();
        List<NetworkVO> zoneNetworks = _networksDao.listByZone(zoneId);

        for (NetworkVO network : zoneNetworks) {
            if (!isNetworkSystem(network)) {
                if (network.getGuestType() == Network.GuestType.Shared || !_networksDao.listBy(accountId, network.getId()).isEmpty()) {
                    if ((type == null || type == network.getGuestType()) && (isDefault == null || isDefault.booleanValue() == network.isDefault)) {
                        accountNetworks.add(network);
                    }
                }
            }
        }
        return accountNetworks;
    }

    @DB
    @Override
    public IPAddressVO markIpAsUnavailable(long addrId) {
        Transaction txn = Transaction.currentTxn();

        IPAddressVO ip = _ipAddressDao.findById(addrId);

        if (ip.getAllocatedToAccountId() == null && ip.getAllocatedTime() == null) {
            s_logger.trace("Ip address id=" + addrId + " is already released");
            return ip;
        }

        if (ip.getState() != State.Releasing) {
            txn.start();

            // don't decrement resource count for direct ips
            if (ip.getAssociatedWithNetworkId() != null) {
                _resourceLimitMgr.decrementResourceCount(_ipAddressDao.findById(addrId).getAccountId(), ResourceType.public_ip);
            }

            long isSourceNat = (ip.isSourceNat()) ? 1 : 0;

            // Save usage event
            if (ip.getAccountId() != Account.ACCOUNT_ID_SYSTEM) {
                VlanVO vlan = _vlanDao.findById(ip.getVlanId());

                String guestType = vlan.getVlanType().toString();

                UsageEventVO usageEvent = new UsageEventVO(EventTypes.EVENT_NET_IP_RELEASE, ip.getAccountId(), ip.getDataCenterId(), addrId, ip.getAddress().addr(), isSourceNat, guestType);
                _usageEventDao.persist(usageEvent);
            }

            ip = _ipAddressDao.markAsUnavailable(addrId);

            txn.commit();
        }

        return ip;
    }

    @Override
    public boolean isNetworkAvailableInDomain(long networkId, long domainId) {
        Long networkDomainId = null;
        Network network = getNetwork(networkId);
        if (network.getGuestType() != Network.GuestType.Shared) {
            s_logger.trace("Network id=" + networkId + " is not shared");
            return false;
        }

        NetworkDomainVO networkDomainMap = _networkDomainDao.getDomainNetworkMapByNetworkId(networkId);
        if (networkDomainMap == null) {
            s_logger.trace("Network id=" + networkId + " is shared, but not domain specific");
            return true;
        } else {
            networkDomainId = networkDomainMap.getDomainId();
        }

        if (domainId == networkDomainId.longValue()) {
            return true;
        }

        if (networkDomainMap.subdomainAccess) {
            Set<Long> parentDomains = _domainMgr.getDomainParentIds(domainId);

            if (parentDomains.contains(domainId)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public Long getDedicatedNetworkDomain(long networkId) {
        NetworkDomainVO networkMaps = _networkDomainDao.getDomainNetworkMapByNetworkId(networkId);
        if (networkMaps != null) {
            return networkMaps.getDomainId();
        } else {
            return null;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_UPDATE, eventDescription = "updating network", async = true)
    public Network updateNetwork(long networkId, String name, String displayText, Account callerAccount, User callerUser, String domainSuffix, Long networkOfferingId) {
        boolean restartNetwork = false;

        // verify input parameters
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Network id=" + networkId + "doesn't exist in the system");
        }

        _accountMgr.checkAccess(callerAccount, null, network);

        // Don't allow to update system network
        NetworkOffering offering = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (offering.isSystemOnly()) {
            throw new InvalidParameterValueException("Can't update system networks");
        }

        if (name != null) {
            network.setName(name);
        }

        if (displayText != null) {
            network.setDisplayText(displayText);
        }

        long oldNetworkOfferingId = network.getNetworkOfferingId();
        if (networkOfferingId != null) {

            NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
            if (networkOffering == null || networkOffering.isSystemOnly()) {
                throw new InvalidParameterValueException("Unable to find network offering by id " + networkOfferingId);
            }

            // Network offering upgrade is allowed for isolated networks only
            if (network.getGuestType() != GuestType.Isolated) {
                throw new InvalidParameterValueException("Network offering upgrade is allowed only for networks with guest type " + GuestType.Isolated);
            }

            //network offering should be in Enabled state
            if (networkOffering.getState() != NetworkOffering.State.Enabled) {
                throw new InvalidParameterValueException("Network offering " + networkOffering + " is not in " + NetworkOffering.State.Enabled + " state, can't upgrade to it");
            }

            if (networkOfferingId != oldNetworkOfferingId) {
                //check if the network is upgradable
                if (!canUpgrade(oldNetworkOfferingId, networkOfferingId)) {
                    throw new InvalidParameterValueException("Can't upgrade from network offering " + oldNetworkOfferingId + " to " + networkOfferingId + "; check logs for more information");
                }
                restartNetwork = true;
            }
        }

        //don't allow to modify network domain if the service is not supported
        if (domainSuffix != null) {
            // validate network domain
            if (!NetUtils.verifyDomainName(domainSuffix)) {
                throw new InvalidParameterValueException(
                        "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
            }

            //don't allow to update shared network
            if (offering.getGuestType() != GuestType.Isolated) {
                throw new InvalidParameterValueException("networkDomain can be upgraded only for the network of type " + GuestType.Isolated);
            }

            long offeringId = oldNetworkOfferingId;
            if (networkOfferingId != null) {
                offeringId = networkOfferingId;
            }

            Map<Network.Capability, String> dnsCapabilities = getNetworkOfferingServiceCapabilities(_configMgr.getNetworkOffering(offeringId), Service.Dns);
            String isUpdateDnsSupported = dnsCapabilities.get(Capability.AllowDnsSuffixModification);
            if (isUpdateDnsSupported == null || !Boolean.valueOf(isUpdateDnsSupported)) {
                throw new InvalidParameterValueException("Domain name change is not supported by the network offering id=" + networkOfferingId);
            }

            network.setNetworkDomain(domainSuffix);
            //have to restart the network
            restartNetwork = true;
        }

        //1) Shutdown all the elements and cleanup all the rules
        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        if (restartNetwork) {
            s_logger.debug("Shutting down elements and resources for network id=" + networkId + " as a part of network update");

            if (!shutdownNetworkElementsAndResources(context, true, network)) {
                s_logger.warn("Failed to shutdown the network elements and resources as a part of network restart: " + network.getState());
                throw new CloudRuntimeException("Failed to shutdown the network elements and resources as a part of network restart: " + network.getState());
            }
        }

        //2) Only after all the elements and rules are shutdown properly, update the network VO
        if (networkOfferingId != null) {
            network.setNetworkOfferingId(networkOfferingId);
            _networksDao.update(networkId, network, finalizeServicesAndProvidersForNetwork(_configMgr.getNetworkOffering(networkOfferingId), network.getPhysicalNetworkId()));
        } else {
            _networksDao.update(networkId, network); 
        }

        //get updated network
        network = _networksDao.findById(networkId);

        //3) Implement the elements and rules again
        if (restartNetwork) {
            DeployDestination dest = new DeployDestination(_dcDao.findById(network.getDataCenterId()), null, null, null);

            s_logger.debug("Implementing the network " + network + " elements and resources as a part of network update");        
            try {
                implementNetworkElementsAndResources(dest, context, network, _networkOfferingDao.findById(network.getNetworkOfferingId()));
            } catch (Exception ex) {
                s_logger.warn("Failed to implement network " + network + " elements and resources as a part of network update due to ", ex);
                throw new CloudRuntimeException("Failed to implement network " + network + " elements and resources as a part of network update");
            }
        }

        return getNetwork(network.getId());
    }

    @Override
    public Integer getNetworkRate(long networkId, Long vmId) {
        VMInstanceVO vm = null;
        if (vmId != null) {
            vm = _vmDao.findById(vmId);
        }
        Network network = getNetwork(networkId);
        NetworkOffering ntwkOff = _configMgr.getNetworkOffering(network.getNetworkOfferingId());

        // For default userVm Default network and domR guest/public network, get rate information from the service offering; for other situations get information
        // from the network offering
        boolean isUserVmsDefaultNetwork = false;
        boolean isDomRGuestOrPublicNetwork = false;
        if (vm != null) {
            if (vm.getType() == Type.User && network.isDefault()) {
                isUserVmsDefaultNetwork = true; 
            } else if (vm.getType() == Type.DomainRouter && (ntwkOff.getTrafficType() == TrafficType.Public || ntwkOff.getTrafficType() == TrafficType.Guest)) {
                isDomRGuestOrPublicNetwork = true;
            }    
        }
        if (isUserVmsDefaultNetwork || isDomRGuestOrPublicNetwork) {
            return _configMgr.getServiceOfferingNetworkRate(vm.getServiceOfferingId());
        } else {
            return _configMgr.getNetworkOfferingNetworkRate(ntwkOff.getId());
        }
    }

    Random _rand = new Random(System.currentTimeMillis());

    @Override
    @DB
    public String acquireGuestIpAddress(Network network, String requestedIp) {
        List<String> ips = _nicDao.listIpAddressInNetwork(network.getId());
        String[] cidr = network.getCidr().split("/");
        Set<Long> allPossibleIps = NetUtils.getAllIpsFromCidr(cidr[0], Integer.parseInt(cidr[1]));
        Set<Long> usedIps = new TreeSet<Long>();

        if (requestedIp != null && requestedIp.equals(network.getGateway())) {
            s_logger.warn("Requested ip address " + requestedIp + " is used as a gateway address in network " + network);
            return null;
        }

        for (String ip : ips) {
            if (requestedIp != null && requestedIp.equals(ip)) {
                s_logger.warn("Requested ip address " + requestedIp + " is already in use in network " + network);
                return null;
            }

            usedIps.add(NetUtils.ip2Long(ip));
        }
        if (usedIps.size() != 0) {
            allPossibleIps.removeAll(usedIps);
        }
        if (allPossibleIps.isEmpty()) {
            return null;
        }

        Long[] array = allPossibleIps.toArray(new Long[allPossibleIps.size()]);

        if (requestedIp != null) {
            //check that requested ip has the same cidr
            boolean isSameCidr = NetUtils.sameSubnetCIDR(requestedIp, NetUtils.long2Ip(array[0]), Integer.parseInt(cidr[1]));
            if (!isSameCidr) {
                s_logger.warn("Requested ip address " + requestedIp + " doesn't belong to the network " + network + " cidr");
                return null;
            } else {
                return requestedIp;
            }
        }

        String result;
        do {
            result = NetUtils.long2Ip(array[_rand.nextInt(array.length)]);
        } while (result.split("\\.")[3].equals("1"));
        return result;
    }


    private String getZoneNetworkDomain(long zoneId) {
        return _dcDao.findById(zoneId).getDomain();
    }

    private String getDomainNetworkDomain(long domainId, long zoneId) {
        String networkDomain = _domainDao.findById(domainId).getNetworkDomain();
        if (networkDomain == null) {
            return getZoneNetworkDomain(zoneId);
        }

        return networkDomain;
    }

    private String getAccountNetworkDomain(long accountId, long zoneId) {
        String networkDomain = _accountDao.findById(accountId).getNetworkDomain();

        if (networkDomain == null) {
            //get domain level network domain
            return getDomainNetworkDomain(_accountDao.findById(accountId).getDomainId(), zoneId);
        }

        return networkDomain;
    }

    @Override
    public String getGlobalGuestDomainSuffix() {
        return _networkDomain;
    }

    @Override
    public String getStartIpAddress(long networkId) {
        List<VlanVO> vlans = _vlanDao.listVlansByNetworkId(networkId);
        if (vlans.isEmpty()) {
            return null;
        }

        String startIP = vlans.get(0).getIpRange().split("-")[0];

        for (VlanVO vlan : vlans) {
            String startIP1 = vlan.getIpRange().split("-")[0];
            long startIPLong = NetUtils.ip2Long(startIP);
            long startIPLong1 = NetUtils.ip2Long(startIP1);

            if (startIPLong1 < startIPLong) {
                startIP = startIP1; 
            }
        }

        return startIP;
    }

    @Override
    public boolean applyStaticNats(List<? extends StaticNat> staticNats, boolean continueOnError) throws ResourceUnavailableException {
        if (staticNats == null || staticNats.size() == 0) {
            s_logger.debug("There are no static nat rules for the network elements");
            return true;
        }

        boolean success = true;
        Network network = _networksDao.findById(staticNats.get(0).getNetworkId());
        for (NetworkElement ne : _networkElements) {
            try {
                if (!(ne instanceof StaticNatServiceProvider)) {
                    continue;
                }
                boolean handled = ((StaticNatServiceProvider)ne).applyStaticNats(network, staticNats);
                s_logger.debug("Static Nat for network " + network.getId() + " were " + (handled ? "" : " not") + " handled by " + ne.getName());
            } catch (ResourceUnavailableException e) {
                if (!continueOnError) {
                    throw e;
                }
                s_logger.warn("Problems with " + ne.getName() + " but pushing on", e);
                success = false;
            }
        }

        return success;
    }

    @Override
    public Long getPodIdForVlan(long vlanDbId) {
        PodVlanMapVO podVlanMaps = _podVlanMapDao.listPodVlanMapsByVlan(vlanDbId);
        if (podVlanMaps == null) {
            return null;
        } else {
            return podVlanMaps.getPodId();
        }
    }

    @DB
    @Override
    public boolean reallocate(VirtualMachineProfile<? extends VMInstanceVO> vm, DataCenterDeployment dest) throws InsufficientCapacityException, ConcurrentOperationException {
        VMInstanceVO vmInstance = _vmDao.findById(vm.getId());
        DataCenterVO dc = _dcDao.findById(vmInstance.getDataCenterIdToDeployIn());
        if (dc.getNetworkType() == NetworkType.Basic) {
            List<NicVO> nics = _nicDao.listByVmId(vmInstance.getId());
            NetworkVO network = _networksDao.findById(nics.get(0).getNetworkId());
            Pair<NetworkVO, NicProfile> profile = new Pair<NetworkVO, NicProfile>(network, null);
            List<Pair<NetworkVO, NicProfile>> profiles = new ArrayList<Pair<NetworkVO, NicProfile>>();
            profiles.add(profile);

            Transaction txn = Transaction.currentTxn();
            txn.start();

            try {
                this.cleanupNics(vm);
                this.allocate(vm, profiles);
            } finally {
                txn.commit();
            }
        }
        return true;
    }

    @Override
    public Map<String, Set<String>> listNetworkOfferingServices(long networkOfferingId) {
        Map<String, Set<String>> serviceProviderMap = new HashMap<String, Set<String>>();
        List<NetworkOfferingServiceMapVO> map = _ntwkOfferingSrvcDao.listByNetworkOfferingId(networkOfferingId);

        for (NetworkOfferingServiceMapVO instance : map) {
            String service = instance.getService();
            Set<String> providers;
            if (serviceProviderMap.containsKey(service)) {
                providers = serviceProviderMap.get(service);
            } else {
                providers = new HashSet<String>();
            }
            providers.add(instance.getProvider());
            serviceProviderMap.put(service, providers);
        }

        return serviceProviderMap;
    }

    @Override
    public boolean isProviderSupportServiceInNetwork(long networkId, Service service, Provider provider){
        return _ntwkSrvcDao.canProviderSupportServiceInNetwork(networkId, service, provider);
    }

    protected boolean canUpgrade(long oldNetworkOfferingId, long newNetworkOfferingId) {
        NetworkOffering oldNetworkOffering = _networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId);
        NetworkOffering newNetworkOffering = _networkOfferingDao.findById(newNetworkOfferingId);

        //can upgrade only Isolated networks
        if (oldNetworkOffering.getGuestType() != GuestType.Isolated) {
            throw new InvalidParameterValueException("NetworkOfferingId can be upgraded only for the network of type " + GuestType.Isolated);
        }

        //security group service should be the same
        if (areServicesSupportedByNetworkOffering(oldNetworkOfferingId, Service.SecurityGroup) != areServicesSupportedByNetworkOffering(newNetworkOfferingId, Service.SecurityGroup)) {
            s_logger.debug("Offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId + " have different securityGroupProperty, can't upgrade");
            return false;
        }

        //Type of the network should be the same
        if (oldNetworkOffering.getGuestType() != newNetworkOffering.getGuestType()){
            s_logger.debug("Network offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId +  " are of different types, can't upgrade");
            return false;
        }

        //tags should be the same
        if (newNetworkOffering.getTags() != null) {
            if (oldNetworkOffering.getTags() == null) {
                s_logger.debug("New network offering id=" + newNetworkOfferingId + " has tags and old network offering id=" + oldNetworkOfferingId +  " doesn't, can't upgrade");
                return false;
            }
            if (!oldNetworkOffering.getTags().equalsIgnoreCase(newNetworkOffering.getTags())) {
                s_logger.debug("Network offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId +  " have different tags, can't upgrade");
                return false;
            }
        }

        //Traffic types should be the same 
        if (oldNetworkOffering.getTrafficType() != newNetworkOffering.getTrafficType()) {
            s_logger.debug("Network offerings " + newNetworkOfferingId + " and " + oldNetworkOfferingId +  " have different traffic types, can't upgrade");
            return false;
        }

        return true;
    }


    protected boolean canUpgradeProviders(long oldNetworkOfferingId, long newNetworkOfferingId) {
        //list of services and providers should be the same
        Map<String, Set<String>> newServices = listNetworkOfferingServices(newNetworkOfferingId);
        Map<String, Set<String>> oldServices = listNetworkOfferingServices(oldNetworkOfferingId);

        if (newServices.size() < oldServices.size()) {
            s_logger.debug("Network offering downgrade is not allowed: number of supported services for the new offering " + newNetworkOfferingId + " is less than the old offering " + oldNetworkOfferingId);
            return false;
        }

        for (String service : oldServices.keySet()) {

            //1)check that all old services are present in the new network offering
            if (!newServices.containsKey(service)) {
                s_logger.debug("New service offering doesn't have " + service + " service present in the old service offering, downgrade is not allowed");
                return false;
            }  

            Set<String> newProviders = newServices.get(service);
            Set<String> oldProviders = oldServices.get(service);

            //2) Can upgrade only from internal provider to external provider. Any other combinations are not allowed
            for (String oldProvider : oldProviders) {
                if (newProviders.contains(oldProvider)) {
                    s_logger.trace("New list of providers contains provider " + oldProvider);
                    continue;
                }
                //iterate through new providers and check that the old provider can upgrade
                for (String newProvider : newProviders) {
                    if (!(!Provider.getProvider(oldProvider).isExternal() && Provider.getProvider(newProvider).isExternal())) {
                        s_logger.debug("Can't downgrade from network offering " + oldNetworkOfferingId + " to the new networkOffering " + newNetworkOfferingId);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_CREATE, eventDescription = "Creating Physical Network", create = true)
    public PhysicalNetwork createPhysicalNetwork(Long zoneId, String vnetRange, String networkSpeed, List<String> isolationMethods, String broadcastDomainRangeStr, Long domainId, List<String> tags) {
        // Check if zone exists
        if (zoneId == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }

        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }

        if(zone.getNetworkType() == NetworkType.Basic){
            if(!_physicalNetworkDao.listByZone(zoneId).isEmpty()){
                throw new CloudRuntimeException("Cannot add the physical network to basic zone id: "+zoneId+", there is a physical network already existing in this basic Zone");
            }
        }
        if (tags != null && tags.size() > 1) {
            throw new InvalidParameterException("Only one tag can be specified for a physical network at this time");
        }

        if (isolationMethods != null && isolationMethods.size() > 1) {
            throw new InvalidParameterException("Only one isolationMethod can be specified for a physical network at this time");
        }

        int vnetStart = 0;
        int vnetEnd = 0;
        if (vnetRange != null) {

            //Verify zone type
            if (zone.getNetworkType() == NetworkType.Basic
                    || (zone.getNetworkType() == NetworkType.Advanced && zone.isSecurityGroupEnabled())) {
                throw new InvalidParameterValueException("Can't add vnet range to the physical network in the zone that supports " + zone.getNetworkType() + " network, Security Group enabled: "+ zone.isSecurityGroupEnabled());
            }

            String[] tokens = vnetRange.split("-");
            try {
                vnetStart = Integer.parseInt(tokens[0]);
                if (tokens.length == 1) {
                    vnetEnd = vnetStart;
                } else {
                    vnetEnd = Integer.parseInt(tokens[1]);
                }
            } catch (NumberFormatException e) {
                throw new InvalidParameterValueException("Please specify valid integers for the vlan range.");
            }

            if ((vnetStart > vnetEnd) || (vnetStart < 0) || (vnetEnd > 4096)) {
                s_logger.warn("Invalid vnet range: start range:" + vnetStart + " end range:" + vnetEnd);
                throw new InvalidParameterValueException("Vnet range should be between 0-4096 and start range should be lesser than or equal to end range");
            }
        }

        BroadcastDomainRange broadcastDomainRange = null;
        if (broadcastDomainRangeStr != null && !broadcastDomainRangeStr.isEmpty()) {
            try {
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.valueOf(broadcastDomainRangeStr.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve broadcastDomainRange '" + broadcastDomainRangeStr + "' to a supported value {Pod or Zone}");
            }
        }

        if(broadcastDomainRange == null){
            if(zone.getNetworkType() == NetworkType.Basic){
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.POD;
            }else{
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.ZONE;
            }
        }

        Transaction txn = Transaction.currentTxn();
        try {
            txn.start();
            // Create the new physical network in the database
            PhysicalNetworkVO pNetwork = new PhysicalNetworkVO(zoneId, vnetRange, networkSpeed, domainId, broadcastDomainRange);
            pNetwork.setTags(tags);
            pNetwork.setIsolationMethods(isolationMethods);

            pNetwork = _physicalNetworkDao.persist(pNetwork);

            // Add vnet entries for the new zone if zone type is Advanced
            if (vnetRange != null) {
                _dcDao.addVnet(zone.getId(), pNetwork.getId(), vnetStart, vnetEnd);
            }

            txn.commit();
            return pNetwork;
        } catch (Exception ex) {
            s_logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to create a physical network");
        }
    }

    @Override
    public List<? extends PhysicalNetwork> searchPhysicalNetworks(Long id, Long zoneId, String keyword, Long startIndex, Long pageSize){
        Filter searchFilter = new Filter(PhysicalNetworkVO.class, "id", Boolean.TRUE, startIndex, pageSize);
        SearchCriteria<PhysicalNetworkVO> sc = _physicalNetworkDao.createSearchCriteria();

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }
        return _physicalNetworkDao.search(sc, searchFilter);     
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_UPDATE, eventDescription = "updating physical network", async = true)
    public PhysicalNetwork updatePhysicalNetwork(Long id, String networkSpeed, List<String> tags, String newVnetRangeString, String state) {

        // verify input parameters
        PhysicalNetworkVO network = _physicalNetworkDao.findById(id);
        if (network == null) {
            throw new InvalidParameterValueException("Physical Network id=" + id + "doesn't exist in the system");
        }

        // if zone is of Basic type, don't allow to add vnet range
        DataCenter zone = _dcDao.findById(network.getDataCenterId());
        if (zone == null) {
            throw new InvalidParameterValueException("Zone with id=" + network.getDataCenterId() + " doesn't exist in the system");
        }
        if(newVnetRangeString != null){
            if (zone.getNetworkType() == NetworkType.Basic
                    || (zone.getNetworkType() == NetworkType.Advanced && zone.isSecurityGroupEnabled())) {
                throw new InvalidParameterValueException("Can't add vnet range to the physical network in the zone that supports " + zone.getNetworkType() + " network, Security Group enabled: "+ zone.isSecurityGroupEnabled());
            }
        }


        if (tags != null && tags.size() > 1) {
            throw new InvalidParameterException("Unable to support more than one tag on network yet");
        }

        PhysicalNetwork.State networkState = null;
        if (state != null && !state.isEmpty()) {
            try {
                networkState = PhysicalNetwork.State.valueOf(state);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve state '" + state + "' to a supported value {Enabled or Disabled}");
            }
        }

        if(state != null){
            network.setState(networkState);
        }

        if (tags != null) {
            network.setTags(tags);
        }

        if(networkSpeed != null){
            network.setSpeed(networkSpeed);
        }

        // Vnet range can be extended only
        boolean replaceVnet = false;
        ArrayList<Pair<Integer, Integer>> vnetsToAdd = new ArrayList<Pair<Integer, Integer>>(2); 

        if (newVnetRangeString != null) {
            Integer newStartVnet = 0;
            Integer newEndVnet = 0;
            String[] newVnetRange = newVnetRangeString.split("-");

            if (newVnetRange.length < 2) {
                throw new InvalidParameterValueException("Please provide valid vnet range between 0-4096");
            }

            if (newVnetRange[0] == null || newVnetRange[1] == null) {
                throw new InvalidParameterValueException("Please provide valid vnet range between 0-4096");
            }

            try {
                newStartVnet = Integer.parseInt(newVnetRange[0]);
                newEndVnet = Integer.parseInt(newVnetRange[1]);
            } catch (NumberFormatException e) {
                s_logger.warn("Unable to parse vnet range:", e);
                throw new InvalidParameterValueException("Please provide valid vnet range between 0-4096");
            }

            if (newStartVnet < 0 || newEndVnet > 4096) {
                throw new InvalidParameterValueException("Vnet range has to be between 0-4096");
            }

            if (newStartVnet > newEndVnet) {
                throw new InvalidParameterValueException("Vnet range has to be between 0-4096 and start range should be lesser than or equal to stop range");
            } 

            if (physicalNetworkHasAllocatedVnets(network.getDataCenterId(), network.getId())) {
                String[] existingRange = network.getVnet().split("-");
                int existingStartVnet = Integer.parseInt(existingRange[0]);
                int existingEndVnet = Integer.parseInt(existingRange[1]);

                //check if vnet is being extended
                if (!(newStartVnet.intValue() > existingStartVnet && newEndVnet.intValue() < existingEndVnet)) {
                    throw new InvalidParameterValueException("Can's shrink existing vnet range as it the range has vnets allocated. Only extending existing vnet is supported");
                }

                if (newStartVnet < existingStartVnet) {
                    vnetsToAdd.add(new Pair<Integer, Integer>(newStartVnet, existingStartVnet - 1));
                }

                if (newEndVnet > existingEndVnet) {
                    vnetsToAdd.add(new Pair<Integer, Integer>(existingEndVnet + 1, newEndVnet));
                }

            } else {
                vnetsToAdd.add(new Pair<Integer, Integer>(newStartVnet, newEndVnet));
                replaceVnet = true;
            }
        }

        if (newVnetRangeString != null) {
            network.setVnet(newVnetRangeString);
        }


        _physicalNetworkDao.update(id, network);

        if (replaceVnet) {
            s_logger.debug("Deleting existing vnet range for the physicalNetwork id= "+id +" and zone id=" + network.getDataCenterId() + " as a part of updatePhysicalNetwork call");
            _dcDao.deleteVnet(network.getId());
        }

        for (Pair<Integer, Integer> vnetToAdd : vnetsToAdd) {
            s_logger.debug("Adding vnet range " + vnetToAdd.first() + "-" + vnetToAdd.second() + " for the physicalNetwork id= "+id +" and zone id=" + network.getDataCenterId() + " as a part of updatePhysicalNetwork call");
            _dcDao.addVnet(network.getDataCenterId(), network.getId(), vnetToAdd.first(), vnetToAdd.second());
        }

        return network;
    }

    private boolean physicalNetworkHasAllocatedVnets(long zoneId, long physicalNetworkId) {
        return !_dcDao.listAllocatedVnets(physicalNetworkId).isEmpty();
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_DELETE, eventDescription = "deleting physical network", async = true)
    public boolean deletePhysicalNetwork(Long physicalNetworkId) {

        // verify input parameters
        PhysicalNetworkVO pNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNetwork == null) {
            throw new InvalidParameterValueException("Network id=" + physicalNetworkId + "doesn't exist in the system");
        }

        checkIfPhysicalNetworkIsDeletable(physicalNetworkId);


        // delete vlans for this zone
        List<VlanVO> vlans = _vlanDao.listVlansByPhysicalNetworkId(physicalNetworkId);
        for (VlanVO vlan : vlans) {
            _vlanDao.remove(vlan.getId());
        }

        // Delete networks
        List<NetworkVO> networks = _networksDao.listByPhysicalNetwork(physicalNetworkId);
        if (networks != null && !networks.isEmpty()) {
            for (NetworkVO network : networks) {
                _networksDao.remove(network.getId());
            }
        }

        //delete vnets
        _dcDao.deleteVnet(physicalNetworkId);

        //delete service providers
        _pNSPDao.deleteProviders(physicalNetworkId);

        boolean success = _physicalNetworkDao.remove(physicalNetworkId);


        return success;
    }

    @DB
    private void checkIfPhysicalNetworkIsDeletable(Long physicalNetworkId) {
        List<List<String>> tablesToCheck = new ArrayList<List<String>>();

        List<String> vnet = new ArrayList<String>();
        vnet.add(0, "op_dc_vnet_alloc");
        vnet.add(1, "physical_network_id");
        vnet.add(2, "there are allocated vnets for this physical network");
        tablesToCheck.add(vnet);

        List<String> networks = new ArrayList<String>();
        networks.add(0, "networks");
        networks.add(1, "physical_network_id");
        networks.add(2, "there are networks associated to this physical network");
        tablesToCheck.add(networks);

        /*List<String> privateIP = new ArrayList<String>();
        privateIP.add(0, "op_dc_ip_address_alloc");
        privateIP.add(1, "data_center_id");
        privateIP.add(2, "there are private IP addresses allocated for this zone");
        tablesToCheck.add(privateIP);*/

        List<String> publicIP = new ArrayList<String>();
        publicIP.add(0, "user_ip_address");
        publicIP.add(1, "physical_network_id");
        publicIP.add(2, "there are public IP addresses allocated for this physical network");
        tablesToCheck.add(publicIP);



        for (List<String> table : tablesToCheck) {
            String tableName = table.get(0);
            String column = table.get(1);
            String errorMsg = table.get(2);

            String dbName = "cloud";

            String selectSql = "SELECT * FROM `" + dbName + "`.`" + tableName + "` WHERE " + column + " = ?";

            if (tableName.equals("networks")) {
                selectSql += " AND removed is NULL";
            }

            if (tableName.equals("op_dc_vnet_alloc")) {
                selectSql += " AND taken IS NOT NULL";
            }

            if (tableName.equals("user_ip_address")) {
                selectSql += " AND state!='Free'";
            }

            if (tableName.equals("op_dc_ip_address_alloc")) {
                selectSql += " AND taken IS NOT NULL";
            }

            Transaction txn = Transaction.currentTxn();
            try {
                PreparedStatement stmt = txn.prepareAutoCloseStatement(selectSql);
                stmt.setLong(1, physicalNetworkId);
                ResultSet rs = stmt.executeQuery();
                if (rs != null && rs.next()) {
                    throw new CloudRuntimeException("The Physical Network is not deletable because " + errorMsg);
                }
            } catch (SQLException ex) {
                throw new CloudRuntimeException("The Management Server failed to detect if physical network is deletable. Please contact Cloud Support.");
            }
        }

    }    

    @Override
    public List<? extends Service> listNetworkServices(String providerName){

        Provider provider = null;
        if(providerName != null){
            provider = Network.Provider.getProvider(providerName);
            if(provider == null){
                throw new InvalidParameterValueException("Invalid Network Service Provider=" + providerName);
            }
        }

        if(provider != null){
            NetworkElement element = getElementImplementingProvider(providerName);
            if(element == null){
                throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + providerName + "'");
            }
            return new ArrayList<Service>(element.getCapabilities().keySet());
        }else{
            return Service.listAllServices();
        }
    }

    @Override
    public List<? extends Provider> listSupportedNetworkServiceProviders(String serviceName){
        Network.Service service = null;
        if(serviceName != null){
            service = Network.Service.getService(serviceName);
            if(service == null){
                throw new InvalidParameterValueException("Invalid Network Service=" + serviceName);
            }
        }

        Set<Provider> supportedProviders = new HashSet<Provider>();

        if(service != null){
            supportedProviders.addAll(s_serviceToImplementedProvidersMap.get(service));
        }else{
            for(List<Provider> pList : s_serviceToImplementedProvidersMap.values()){
                supportedProviders.addAll(pList);
            }
        }

        return new ArrayList<Provider>(supportedProviders);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_CREATE, eventDescription = "Creating Physical Network ServiceProvider", create = true)    
    public PhysicalNetworkServiceProvider addProviderToPhysicalNetwork(Long physicalNetworkId, String providerName, Long destinationPhysicalNetworkId, List<String> enabledServices) {

        // verify input parameters
        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            throw new InvalidParameterValueException("Physical Network id=" + physicalNetworkId + "doesn't exist in the system");
        }

        // verify input parameters
        if(destinationPhysicalNetworkId != null){
            PhysicalNetworkVO destNetwork = _physicalNetworkDao.findById(destinationPhysicalNetworkId);
            if (destNetwork == null) {
                throw new InvalidParameterValueException("Destination Physical Network id=" + destinationPhysicalNetworkId + "doesn't exist in the system");
            }
        }

        if(providerName != null){
            Provider provider = Network.Provider.getProvider(providerName);
            if(provider == null){
                throw new InvalidParameterValueException("Invalid Network Service Provider=" + providerName);
            }
        }

        //check if services can be turned off
        NetworkElement element = getElementImplementingProvider(providerName);
        if(element == null){
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + providerName + "'");
        }
        List<Service> services = new ArrayList<Service>();

        if(enabledServices != null){
            if(!element.canEnableIndividualServices()){
                if(enabledServices.size() != element.getCapabilities().keySet().size()){
                    throw new InvalidParameterValueException("Cannot enable subset of Services, Please specify the complete list of Services for this Service Provider '" + providerName + "'");
                }
            }

            //validate Services
            boolean addGatewayService = false;
            for(String serviceName : enabledServices){
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null || service == Service.Gateway){
                    throw new InvalidParameterValueException("Invalid Network Service specified=" + serviceName);
                } else if (service == Service.SourceNat) {
                    addGatewayService = true;
                }

                //check if the service is provided by this Provider
                if(!element.getCapabilities().containsKey(service)){
                    throw new InvalidParameterValueException(providerName+" Provider cannot provide this Service specified=" + serviceName);
                }
                services.add(service);
            }

            if (addGatewayService) {
                services.add(Service.Gateway);
            }
        }else{
            //enable all the default services supported by this element.
            services = new ArrayList<Service>(element.getCapabilities().keySet()); 
        }

        Transaction txn = Transaction.currentTxn();
        try {
            txn.start();
            // Create the new physical network in the database
            PhysicalNetworkServiceProviderVO nsp = new PhysicalNetworkServiceProviderVO(physicalNetworkId, providerName);
            //set enabled services
            nsp.setEnabledServices(services);

            if(destinationPhysicalNetworkId != null){
                nsp.setDestinationPhysicalNetworkId(destinationPhysicalNetworkId);
            }
            nsp = _pNSPDao.persist(nsp);

            txn.commit();
            return nsp;
        } catch (Exception ex) {
            s_logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to add a provider to physical network");
        }

    }

    @Override
    public List<? extends PhysicalNetworkServiceProvider> listNetworkServiceProviders(Long physicalNetworkId) {
        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            throw new InvalidParameterValueException("Physical Network id=" + physicalNetworkId + "doesn't exist in the system");
        }

        return _pNSPDao.listBy(physicalNetworkId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_UPDATE, eventDescription = "Updating physical network ServiceProvider", async = true)
    public PhysicalNetworkServiceProvider updateNetworkServiceProvider(Long id, String stateStr, List<String> enabledServices){

        PhysicalNetworkServiceProviderVO provider = _pNSPDao.findById(id);
        if(provider == null){
            throw new InvalidParameterValueException("Network Service Provider id=" + id + "doesn't exist in the system");
        }

        NetworkElement element = getElementImplementingProvider(provider.getProviderName());
        if(element == null){
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getProviderName() + "'");
        }

        PhysicalNetworkServiceProvider.State state = null;
        if (stateStr != null && !stateStr.isEmpty()) {
            try {
                state = PhysicalNetworkServiceProvider.State.valueOf(stateStr);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve state '" + stateStr + "' to a supported value {Enabled or Disabled}");
            }
        }

        boolean update = false;

        if(state != null){
            if(state == PhysicalNetworkServiceProvider.State.Shutdown){
                throw new InvalidParameterValueException("Updating the provider state to 'Shutdown' is not supported");
            }

            if(s_logger.isDebugEnabled()){
                s_logger.debug("updating state of the service provider id=" + id + " on physical network: "+provider.getPhysicalNetworkId() + " to state: "+stateStr);
            }
            switch(state) {
            case Enabled:
                if(element != null && element.isReady(provider)){
                    provider.setState(PhysicalNetworkServiceProvider.State.Enabled);
                    update = true;
                }else{
                    throw new CloudRuntimeException("Provider is not ready, cannot Enable the provider, please configure the provider first");                        
                }
                break;
            case Disabled:
                //do we need to do anything for the provider instances before disabling?
                provider.setState(PhysicalNetworkServiceProvider.State.Disabled);
                update = true;
                break;
            }
        }

        if(enabledServices != null){
            //check if services can be turned of
            if(!element.canEnableIndividualServices()){
                throw new InvalidParameterValueException("Cannot update set of Services for this Service Provider '" + provider.getProviderName() + "'");
            }

            //validate Services
            List<Service> services = new ArrayList<Service>();
            for(String serviceName : enabledServices){
                Network.Service service = Network.Service.getService(serviceName);
                if(service == null){
                    throw new InvalidParameterValueException("Invalid Network Service specified=" + serviceName);
                }
                services.add(service);
            }
            //set enabled services
            provider.setEnabledServices(services);
            update = true;
        }

        if(update){
            _pNSPDao.update(id, provider);
        }
        return provider;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_DELETE, eventDescription = "Deleting physical network ServiceProvider", async = true)
    public boolean deleteNetworkServiceProvider(Long id) throws ConcurrentOperationException, ResourceUnavailableException {
        PhysicalNetworkServiceProviderVO provider = _pNSPDao.findById(id);

        if(provider == null){
            throw new InvalidParameterValueException("Network Service Provider id=" + id + "doesn't exist in the system");
        }

        //check if there are networks using this provider
        List<NetworkVO> networks = _networksDao.listByPhysicalNetworkAndProvider(provider.getPhysicalNetworkId(), provider.getProviderName());
        if(networks != null && !networks.isEmpty()){
            throw new CloudRuntimeException("Provider is not deletable because there are active networks using this provider, please upgrade these networks to new network offerings");
        }

        User callerUser = _accountMgr.getActiveUser(UserContext.current().getCallerUserId());
        Account callerAccount = _accountMgr.getActiveAccountById(callerUser.getAccountId());
        //shutdown the provider instances
        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        if(s_logger.isDebugEnabled()){
            s_logger.debug("Shutting down the service provider id=" + id + " on physical network: "+provider.getPhysicalNetworkId());
        }
        NetworkElement element = getElementImplementingProvider(provider.getProviderName());
        if(element == null){
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getProviderName() + "'");
        }

        if(element != null && element.shutdownProviderInstances(provider, context)){
            provider.setState(PhysicalNetworkServiceProvider.State.Shutdown);
        }

        return _pNSPDao.remove(id);
    }

    @Override
    public PhysicalNetwork getPhysicalNetwork(Long physicalNetworkId){
        return _physicalNetworkDao.findById(physicalNetworkId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_CREATE, eventDescription = "Creating Physical Network", async = true)
    public PhysicalNetwork getCreatedPhysicalNetwork(Long physicalNetworkId) {
        return getPhysicalNetwork(physicalNetworkId);
    }

    @Override
    public PhysicalNetworkServiceProvider getPhysicalNetworkServiceProvider(Long providerId) {
        return _pNSPDao.findById(providerId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_CREATE, eventDescription = "Creating Physical Network ServiceProvider", async = true)
    public PhysicalNetworkServiceProvider getCreatedPhysicalNetworkServiceProvider(Long providerId) {
        return getPhysicalNetworkServiceProvider(providerId);
    }


    @Override
    public long findPhysicalNetworkId(long zoneId, String tag) {
        List<PhysicalNetworkVO> pNtwks = _physicalNetworkDao.listByZone(zoneId);
        if (pNtwks.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find physical network in zone id=" + zoneId);
        }

        if (pNtwks.size() > 1) {
            if (tag == null) {
                throw new InvalidParameterValueException("More than one physical networks exist in zone id=" + zoneId + " and no tags are specified in order to make a choice");
            }

            Long pNtwkId = null;
            for (PhysicalNetwork pNtwk : pNtwks) {
                if (pNtwk.getTags().contains(tag)) {
                    s_logger.debug("Found physical network id=" + pNtwk.getId() + " based on requested tags " + tag);
                    pNtwkId = pNtwk.getId();
                    break;
                }
            }
            if (pNtwkId == null) {
                throw new InvalidParameterValueException("Unable to find physical network which match the tags " + tag);
            }

            return pNtwkId;
        } else {
            return pNtwks.get(0).getId();
        }
    }

    @Override
    public PhysicalNetwork translateZoneIdToPhysicalNetwork(long zoneId) {
        List<PhysicalNetworkVO> pNtwks = _physicalNetworkDao.listByZone(zoneId);
        if (pNtwks.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find physical network in zone id=" + zoneId);
        }

        if (pNtwks.size() > 1) {
            throw new InvalidParameterValueException("More than one physical networks exist in zone id=" + zoneId);
        }

        return pNtwks.get(0);
    }

    @Override
    public List<Long> listNetworkOfferingsForUpgrade(long networkId) {
        List<Long> offeringsToReturn = new ArrayList<Long>();
        NetworkOffering originalOffering = _configMgr.getNetworkOffering(getNetwork(networkId).getNetworkOfferingId());

        boolean securityGroupSupportedByOriginalOff = areServicesSupportedByNetworkOffering(originalOffering.getId(), Service.SecurityGroup);

        //security group supported property should be the same

        List<Long> offerings = _networkOfferingDao.getOfferingIdsToUpgradeFrom(originalOffering);

        for (Long offeringId : offerings) {
            if (areServicesSupportedByNetworkOffering(offeringId, Service.SecurityGroup) == securityGroupSupportedByOriginalOff) {
                offeringsToReturn.add(offeringId);
            }
        }

        return offeringsToReturn;
    }


    private boolean cleanupNetworkResources(long networkId, Account caller, long callerUserId) {
        boolean success = true;
        Network network = getNetwork(networkId);

        // remove all PF/Static Nat rules for the network
        try {
            if (_rulesMgr.revokeAllPFStaticNatRulesForNetwork(networkId, callerUserId, caller)) {
                s_logger.debug("Successfully cleaned up portForwarding/staticNat rules for network id=" + networkId);
            } else {
                success = false;
                s_logger.warn("Failed to release portForwarding/StaticNat rules as a part of network id=" + networkId + " cleanup");
            }
        } catch (ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            s_logger.warn("Failed to release portForwarding/StaticNat rules as a part of network id=" + networkId + " cleanup due to resourceUnavailable ", ex);
        }

        // remove all LB rules for the network
        if (_lbMgr.removeAllLoadBalanacersForNetwork(networkId, caller, callerUserId)) {
            s_logger.debug("Successfully cleaned up load balancing rules for network id=" + networkId);
        } else {
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            success = false;
            s_logger.warn("Failed to cleanup LB rules as a part of network id=" + networkId + " cleanup");
        }

        //revoke all firewall rules for the network
        try {
            if (_firewallMgr.revokeAllFirewallRulesForNetwork(networkId, callerUserId, caller)) {
                s_logger.debug("Successfully cleaned up firewallRules rules for network id=" + networkId);
            } else {
                success = false;
                s_logger.warn("Failed to cleanup Firewall rules as a part of network id=" + networkId + " cleanup");
            }
        } catch (ResourceUnavailableException ex) {
            success = false;
            // shouldn't even come here as network is being cleaned up after all network elements are shutdown
            s_logger.warn("Failed to cleanup Firewall rules as a part of network id=" + networkId + " cleanup due to resourceUnavailable ", ex);
        }

        // release all ip addresses
        List<IPAddressVO> ipsToRelease = _ipAddressDao.listByAssociatedNetwork(networkId, null);
        for (IPAddressVO ipToRelease : ipsToRelease) {
            IPAddressVO ip = markIpAsUnavailable(ipToRelease.getId());
            assert (ip != null) : "Unable to mark the ip address id=" + ipToRelease.getId() + " as unavailable.";
        }

        try {
            if (!applyIpAssociations(network, true)) {
                s_logger.warn("Unable to apply ip address associations for " + network);
                success = false;
            }
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        return success;
    }


    private boolean shutdownNetworkResources(long networkId, Account caller, long callerUserId) {
        //This method cleans up network rules on the backend w/o touching them in the DB
        boolean success = true;

        // Mark all PF rules as revoked and apply them on the backend (not in the DB)
        List<PortForwardingRuleVO> pfRules = _portForwardingRulesDao.listByNetwork(networkId);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + pfRules.size() + " port forwarding rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (PortForwardingRuleVO pfRule : pfRules) {
            s_logger.trace("Marking pf rule " + pfRule + " with Revoke state");
            pfRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(pfRules, true, false)) {
                s_logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup pf rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // Mark all static  rules as revoked and apply them on the backend (not in the DB)
        List<FirewallRuleVO> firewallStaticNatRules = _firewallDao.listByNetworkAndPurpose(networkId, Purpose.StaticNat);
        List<StaticNatRule> staticNatRules = new ArrayList<StaticNatRule>();
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + firewallStaticNatRules.size() + " static nat rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (FirewallRuleVO firewallStaticNatRule : firewallStaticNatRules) {
            s_logger.trace("Marking static nat rule " + firewallStaticNatRule + " with Revoke state");
            IpAddress ip = _ipAddressDao.findById(firewallStaticNatRule.getSourceIpAddressId());
            FirewallRuleVO ruleVO = _firewallDao.findById(firewallStaticNatRule.getId());

            if (ip == null || !ip.isOneToOneNat() || ip.getAssociatedWithVmId() == null) {
                throw new InvalidParameterValueException("Source ip address of the rule id=" + firewallStaticNatRule.getId() + " is not static nat enabled");
            }

            String dstIp = getIpInNetwork(ip.getAssociatedWithVmId(), firewallStaticNatRule.getNetworkId());
            ruleVO.setState(FirewallRule.State.Revoke);
            staticNatRules.add(new StaticNatRuleImpl(ruleVO, dstIp));
        }

        try {
            if (!_firewallMgr.applyRules(staticNatRules, true, false)) {
                s_logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup static nat rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // remove all LB rules for the network
        List<LoadBalancerVO> lbs = _lbDao.listByNetworkId(networkId);
        List<LoadBalancingRule> lbRules = new ArrayList<LoadBalancingRule>();
        for (LoadBalancerVO lb : lbs) {
            s_logger.trace("Marking lb rule " + lb + " with Revoke state");
            lb.setState(FirewallRule.State.Revoke);
            List<LbDestination> dstList = _lbMgr.getExistingDestinations(lb.getId());
            //mark all destination with revoke state
            for (LbDestination dst : dstList) {
                s_logger.trace("Marking lb destination " + dst + " with Revoke state");
                dst.setRevoked(true);
            }

            LoadBalancingRule loadBalancing = new LoadBalancingRule(lb, dstList);
            lbRules.add(loadBalancing);
        }

        try {
            if (!_firewallMgr.applyRules(lbRules, true, false)) {
                s_logger.warn("Failed to cleanup lb rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup lb rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        //revoke all firewall rules for the network w/o applying them on the DB
        List<FirewallRuleVO> firewallRules = _firewallDao.listByNetworkAndPurpose(networkId, Purpose.Firewall);
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Releasing " + firewallRules.size() + " firewall rules for network id=" + networkId + " as a part of shutdownNetworkRules");
        }

        for (FirewallRuleVO firewallRule : firewallRules) {
            s_logger.trace("Marking firewall rule " + firewallRule + " with Revoke state");
            firewallRule.setState(FirewallRule.State.Revoke);
        }

        try {
            if (!_firewallMgr.applyRules(firewallRules, true, false)) {
                s_logger.warn("Failed to cleanup firewall rules as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException ex) {
            s_logger.warn("Failed to cleanup firewall rules as a part of shutdownNetworkRules due to ", ex);
            success = false;
        }

        // Get all ip addresses, mark as releasing and release them on the backend (except for source nat) - DONE
        Network network = getNetwork(networkId);
        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(networkId, null);
        List<PublicIp> publicIpsToRelease = new ArrayList<PublicIp>();
        if (userIps != null && !userIps.isEmpty()) {
            for (IPAddressVO userIp : userIps) {
                if (!userIp.isSourceNat()) {
                    userIp.setState(State.Releasing);
                }
                PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
                publicIpsToRelease.add(publicIp);
            }
        }

        try {
            if (!applyIpAssociations(network, true, publicIpsToRelease)) {
                s_logger.warn("Unable to apply ip address associations for " + network + " as a part of shutdownNetworkRules");
                success = false;
            }
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("We should never get to here because we used true when applyIpAssociations", e);
        }

        return success;
    }

    @Override
    public boolean isSecurityGroupSupportedInNetwork(Network network) {
        Long physicalNetworkId = network.getPhysicalNetworkId();

        //physical network id can be null in Guest Network in Basic zone, so locate the physical network
        if (physicalNetworkId == null) {
            physicalNetworkId = findPhysicalNetworkId(network.getDataCenterId(), null);
        }

        return isServiceEnabledInNetwork(physicalNetworkId, network.getId(), Service.SecurityGroup);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_CREATE, eventDescription = "Creating Physical Network TrafficType", create = true)    
    public PhysicalNetworkTrafficType addTrafficTypeToPhysicalNetwork(Long physicalNetworkId, String trafficTypeStr, String xenLabel, String kvmLabel, String vmwareLabel, String vlan) {

        // verify input parameters
        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            throw new InvalidParameterValueException("Physical Network id=" + physicalNetworkId + "doesn't exist in the system");
        }


        Networks.TrafficType trafficType = null;
        if (trafficTypeStr != null && !trafficTypeStr.isEmpty()) {
            try {
                trafficType = Networks.TrafficType.valueOf(trafficTypeStr);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve trafficType '" + trafficTypeStr + "' to a supported value");
            }
        }

        if(_pNTrafficTypeDao.isTrafficTypeSupported(physicalNetworkId, trafficType)){
            throw new CloudRuntimeException("This physical network already supports the traffic type: "+trafficType);
        }
        //For Storage, Control, Management, Public check if the zone has any other physical network with this traffictype already present
        //If yes, we cant add these traffics to one more physical network in the zone.

        if(TrafficType.isSystemNetwork(trafficType) || TrafficType.Public.equals(trafficType)){
            if(!_physicalNetworkDao.listByZoneAndTrafficType(network.getDataCenterId(), trafficType).isEmpty()){
                throw new CloudRuntimeException("Fail to add the traffic type to physical network because Zone already has a physical network with this traffic type: "+trafficType);
            }
        }

        Transaction txn = Transaction.currentTxn();
        try {
            txn.start();
            // Create the new traffic type in the database
            if(xenLabel == null){
                xenLabel = getDefaultXenNetworkLabel(trafficType);
            }
            PhysicalNetworkTrafficTypeVO pNetworktrafficType = new PhysicalNetworkTrafficTypeVO(physicalNetworkId, trafficType, xenLabel, kvmLabel, vmwareLabel, vlan);
            pNetworktrafficType = _pNTrafficTypeDao.persist(pNetworktrafficType);

            txn.commit();
            return pNetworktrafficType;
        } catch (Exception ex) {
            s_logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to add a traffic type to physical network");
        }

    }

    private String getDefaultXenNetworkLabel(TrafficType trafficType){
        String xenLabel = null;
        switch(trafficType){
            case Public: xenLabel = _configDao.getValue(Config.XenPublicNetwork.key());
            break;
            case Guest: xenLabel = _configDao.getValue(Config.XenGuestNetwork.key());
            break;
            case Storage: xenLabel = _configDao.getValue(Config.XenStorageNetwork1.key());
            break;
            case Management: xenLabel = _configDao.getValue(Config.XenPrivateNetwork.key());
            break;
            case Control: xenLabel = "cloud_link_local_network";
            break;
        }
        return xenLabel;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_CREATE, eventDescription = "Creating Physical Network TrafficType", async = true)    
    public PhysicalNetworkTrafficType getPhysicalNetworkTrafficType(Long id){
        return _pNTrafficTypeDao.findById(id);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_UPDATE, eventDescription = "Updating physical network TrafficType", async = true)
    public PhysicalNetworkTrafficType updatePhysicalNetworkTrafficType(Long id, String xenLabel, String kvmLabel, String vmwareLabel) {

        PhysicalNetworkTrafficTypeVO trafficType = _pNTrafficTypeDao.findById(id);

        if(trafficType == null){
            throw new InvalidParameterValueException("Traffic Type with id=" + id + "doesn't exist in the system");
        }

        if(xenLabel != null){
            trafficType.setXenNetworkLabel(xenLabel);
        }
        if(kvmLabel != null){
            trafficType.setKvmNetworkLabel(kvmLabel);
        }
        if(vmwareLabel != null){
            trafficType.setVmwareNetworkLabel(vmwareLabel);
        }
        _pNTrafficTypeDao.update(id, trafficType);

        return trafficType;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_DELETE, eventDescription = "Deleting physical network TrafficType", async = true)
    public boolean deletePhysicalNetworkTrafficType(Long id) {
        PhysicalNetworkTrafficTypeVO trafficType = _pNTrafficTypeDao.findById(id);

        if(trafficType == null){
            throw new InvalidParameterValueException("Traffic Type with id=" + id + "doesn't exist in the system");
        }

        //check if there are any networks associated to this physical network with this traffic type
        if(TrafficType.Guest.equals(trafficType.getTrafficType())){
            if(!_networksDao.listByPhysicalNetworkTrafficType(trafficType.getPhysicalNetworkId(), trafficType.getTrafficType()).isEmpty()){
                throw new CloudRuntimeException("The Traffic Type is not deletable because there are existing networks with this traffic type:"+trafficType.getTrafficType());
            }
        }        
        return _pNTrafficTypeDao.remove(id);
    }

    @Override
    public List<? extends PhysicalNetworkTrafficType> listTrafficTypes(Long physicalNetworkId) {
        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            throw new InvalidParameterValueException("Physical Network id=" + physicalNetworkId + "doesn't exist in the system");
        }

        return _pNTrafficTypeDao.listBy(physicalNetworkId);
    }


    @Override
    public PhysicalNetwork getDefaultPhysicalNetworkByZoneAndTrafficType(long zoneId, TrafficType trafficType) {

        List<PhysicalNetworkVO> networkList = _physicalNetworkDao.listByZoneAndTrafficType(zoneId, trafficType);

        if (networkList.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find the default physical network with traffic=" + trafficType +" in zone id=" + zoneId);
        }

        if (networkList.size() > 1) {
            throw new InvalidParameterValueException("More than one physical networks exist in zone id=" + zoneId + " with traffic type="+trafficType);
        }

        return networkList.get(0);
    }

    @Override
    public boolean processAnswers(long agentId, long seq, Answer[] answers) {
        return false;
    }

    @Override
    public boolean processCommands(long agentId, long seq, Command[] commands) {
        return false;
    }

    @Override
    public AgentControlAnswer processControlCommand(long agentId, AgentControlCommand cmd) {
        return null;
    }

    @Override
    public void processConnect(HostVO host, StartupCommand cmd, boolean forRebalance) throws ConnectionException {
        if (!(cmd instanceof StartupRoutingCommand )) {
            return;
        } 
        long hostId = host.getId();
        StartupRoutingCommand startup = (StartupRoutingCommand)cmd;

        String dataCenter = startup.getDataCenter();

        long dcId = -1;
        DataCenterVO dc = _dcDao.findByName(dataCenter);
        if (dc == null) {
            try {
                dcId = Long.parseLong(dataCenter);
                dc = _dcDao.findById(dcId);
            } catch (final NumberFormatException e) {
            }
        }
        if (dc == null) {
            throw new IllegalArgumentException("Host " + startup.getPrivateIpAddress() + " sent incorrect data center: " + dataCenter);
        }
        dcId = dc.getId();
        HypervisorType hypervisorType =  startup.getHypervisorType();

        if(s_logger.isDebugEnabled()){
            s_logger.debug("Host's hypervisorType is: "+hypervisorType);
        }
        
        List<PhysicalNetworkSetupInfo> networkInfoList = new ArrayList<PhysicalNetworkSetupInfo>();

        //list all physicalnetworks in the zone & for each get the network names
        List<PhysicalNetworkVO> physicalNtwkList = _physicalNetworkDao.listByZone(dcId);
        for(PhysicalNetworkVO pNtwk : physicalNtwkList){
            String publicName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Public, hypervisorType);
            String privateName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Management, hypervisorType);
            String guestName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Guest, hypervisorType);
            String storageName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Storage, hypervisorType);
            //String controlName = _pNTrafficTypeDao.getNetworkTag(pNtwk.getId(), TrafficType.Control, hypervisorType);
            PhysicalNetworkSetupInfo info = new PhysicalNetworkSetupInfo();
            info.setPhysicalNetworkId(pNtwk.getId());
            info.setGuestNetworkName(guestName);
            info.setPrivateNetworkName(privateName);
            info.setPublicNetworkName(publicName);
            info.setStorageNetworkName(storageName);
            PhysicalNetworkTrafficTypeVO mgmtTraffic = _pNTrafficTypeDao.findBy(pNtwk.getId(), TrafficType.Management);
            if(mgmtTraffic != null){
                String vlan = mgmtTraffic.getVlan();
                info.setMgmtVlan(vlan);
            }
            networkInfoList.add(info);
        }

        //send the names to the agent  
        if(s_logger.isDebugEnabled()){
            s_logger.debug("Sending CheckNetworkCommand to check the Network is setup correctly on Agent");
        }
        CheckNetworkCommand nwCmd = new CheckNetworkCommand(networkInfoList);

        CheckNetworkAnswer answer = (CheckNetworkAnswer) _agentMgr.easySend(hostId, nwCmd);

        if (answer == null) {
            s_logger.warn("Unable to get an answer to the CheckNetworkCommand from agent:" +host.getId());
            throw new ConnectionException(true, "Unable to get an answer to the CheckNetworkCommand from agent: "+host.getId());
        }

        if (!answer.getResult()) {
            s_logger.warn("Unable to setup agent " + hostId + " due to " + ((answer != null)?answer.getDetails():"return null"));
            String msg = "Incorrect Network setup on agent, Reinitialize agent after network names are setup, details : " + answer.getDetails();
            _alertMgr.sendAlert(AlertManager.ALERT_TYPE_HOST, dcId, host.getPodId(), msg, msg);
            throw new ConnectionException(true, msg);
        }else{
            if ( answer.needReconnect() ) {
                throw new ConnectionException(false, "Reinitialize agent after network setup.");
            }
            if(s_logger.isDebugEnabled()){
                s_logger.debug("Network setup is correct on Agent");
            }
            return;
        }
    }

    @Override
    public boolean processDisconnect(long agentId, Status state) {
        return false;
    }

    @Override
    public boolean isRecurring() {
        return false;
    }

    @Override
    public int getTimeout() {
        return 0;
    }

    @Override
    public boolean processTimeout(long agentId, long seq) {
        return false;
    }    

    private boolean isProviderEnabled(PhysicalNetworkServiceProvider provider) {
        if (provider == null || provider.getState() != PhysicalNetworkServiceProvider.State.Enabled) { //TODO: check for other states: Shutdown?
            return false;
        }
        return true;
    }

    @Override 
    public boolean isProviderEnabledInPhysicalNetwork(long physicalNetowrkId, String providerName) {
        PhysicalNetworkServiceProviderVO ntwkSvcProvider = _pNSPDao.findByServiceProvider(physicalNetowrkId, providerName);
        if (ntwkSvcProvider == null) {
            s_logger.warn("Unable to find provider " + providerName + " in physical network id=" + physicalNetowrkId);
            return false;
        }
        return isProviderEnabled(ntwkSvcProvider);
    }

    private boolean isServiceEnabledInNetwork(long physicalNetworkId, long networkId, Service service) {
        //check if the service is supported in the network
        if (!areServicesSupportedInNetwork(networkId, service)) {
            s_logger.debug("Service " + service.getName() + " is not supported in the network id=" + networkId);
            return false;
        }

        //get provider for the service and check if all of them are supported
        String provider = _ntwkSrvcDao.getProviderForServiceInNetwork(networkId, service);

        if (!isProviderEnabledInPhysicalNetwork(physicalNetworkId, provider)) {
            s_logger.debug("Provider " + provider + " is not enabled in physical network id=" + physicalNetworkId);
            return false;
        }

        return true;
    }


    @Override
    public String getNetworkTag(HypervisorType hType, Network network) {
        Long physicalNetworkId = network.getPhysicalNetworkId();

        if (physicalNetworkId == null) {
            List<PhysicalNetworkVO> pNtwks = _physicalNetworkDao.listByZone(network.getDataCenterId());
            if (pNtwks.size() == 1) {
                physicalNetworkId = pNtwks.get(0).getId();
            } else {
                //locate physicalNetwork with supported traffic type
                //We can make this assumptions based on the fact that Public/Management/Control traffic types are supported only in one physical network in the zone in 3.0
                for (PhysicalNetworkVO pNtwk : pNtwks) {
                    if (_pNTrafficTypeDao.isTrafficTypeSupported(pNtwk.getId(), network.getTrafficType())) {
                        physicalNetworkId = pNtwk.getId();
                        break;
                    }
                }
            }
        }

        if (physicalNetworkId == null) {
            assert (false) : "Can't get the physical network";
            s_logger.warn("Can't get the physical network");
            return null;
        }

        return _pNTrafficTypeDao.getNetworkTag(physicalNetworkId, network.getTrafficType(), hType);
    }


    @Override
    public NetworkVO getExclusiveGuestNetwork(long zoneId) {
        List<NetworkVO> networks = _networksDao.listBy(Account.ACCOUNT_ID_SYSTEM, zoneId, GuestType.Shared, TrafficType.Guest);
        if (networks == null || networks.isEmpty()) {
            throw new InvalidParameterValueException("Unable to find network with trafficType " + TrafficType.Guest + " and guestType " + GuestType.Shared + " in zone " + zoneId);
        }

        if (networks.size() > 1) {
            throw new InvalidParameterValueException("Found more than 1 network with trafficType " + TrafficType.Guest + " and guestType " + GuestType.Shared + " in zone " + zoneId);

        }

        return networks.get(0);
    }

    @Override
    public PhysicalNetworkServiceProvider addDefaultVirtualRouterToPhysicalNetwork(long physicalNetworkId) {

        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, Network.Provider.VirtualRouter.getName(), null, null);
        //add instance of the provider
        VirtualRouterElement element = (VirtualRouterElement)getElementImplementingProvider(Network.Provider.VirtualRouter.getName());
        if(element == null){
            throw new CloudRuntimeException("Unable to find the Network Element implementing the VirtualRouter Provider");
        }
        element.addElement(nsp.getId());

        return nsp;
    }


    @Override
    public boolean isNetworkSystem(Network network) {
        NetworkOffering no = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (no.isSystemOnly()) {
            return true;
        } else {
            return false;
        }
    }

    protected Map<String, String> finalizeServicesAndProvidersForNetwork(NetworkOffering offering, Long physicalNetworkId) {
        Map<String, String> svcProviders = new HashMap<String, String>();
        List<NetworkOfferingServiceMapVO> servicesMap = _ntwkOfferingSrvcDao.listByNetworkOfferingId(offering.getId());

        boolean checkPhysicalNetwork = (physicalNetworkId != null) ? true : false;

        for (NetworkOfferingServiceMapVO serviceMap : servicesMap) {
            if (svcProviders.containsKey(serviceMap.getService())) {
                //FIXME - right now we pick up the first provider from the list, need to add more logic based on provider load, etc
                continue;
            }

            String service = serviceMap.getService();
            String provider = serviceMap.getProvider();

            //check that provider is supported
            if (checkPhysicalNetwork) {
                if (!_pNSPDao.isServiceProviderEnabled(physicalNetworkId, provider, service)) {
                    throw new UnsupportedServiceException("Provider " + provider + " doesn't support service " + service + " in physical network id=" + physicalNetworkId);
                }
            }

            svcProviders.put(service, provider);  
        }

        return svcProviders;
    }

    @Override
    public Long getPhysicalNetworkId(Network network) {
        Long physicalNetworkId = network.getPhysicalNetworkId();
        if (physicalNetworkId == null) {
            physicalNetworkId = findPhysicalNetworkId(network.getDataCenterId(), null);
        }
        return physicalNetworkId;
    }

    @Override
    public boolean getAllowSubdomainAccessGlobal() {
        return _allowSubdomainNetworkAccess;
    }

    private List<Provider> getNetworkProviders(long networkId) {
        List<String> providerNames = _ntwkSrvcDao.getDistinctProviders(networkId);
        List<Provider> providers = new ArrayList<Provider>();
        for (String providerName : providerNames) {
            providers.add(Network.Provider.getProvider(providerName));
        }

        return providers;
    }

    @Override
    public boolean isProviderForNetwork(Provider provider, long networkId) {
    	if (_ntwkSrvcDao.isProviderForNetwork(networkId, provider) != null) {
    		return true;
    	} else {
    		return false;
    	}
    }
    
    @Override
    public void canProviderSupportServices(Map<Provider, Set<Service>> providersMap) {    	
    	for (Provider provider : providersMap.keySet()) {
    		//check if services can be turned off
            NetworkElement element = getElementImplementingProvider(provider.getName());
            if(element == null){
                throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getName() + "'");
            }
            
            Set<Service> enabledServices = providersMap.get(provider);
            
            if(enabledServices != null && !enabledServices.isEmpty()){
                if(!element.canEnableIndividualServices()){
                    if(enabledServices.size() != element.getCapabilities().keySet().size()){
                        throw new InvalidParameterValueException("Cannot enable subset of Services, Please specify the complete list of Services for this Service Provider '" + provider.getName() + "'");
                    }
                }
                for(Service service : enabledServices){
                    //check if the service is provided by this Provider
                    if(!element.getCapabilities().containsKey(service)){
                        throw new UnsupportedServiceException(provider.getName() + " Provider cannot provide service " + service.getName());
                    }
                }
            }	
    	}
    }
}
