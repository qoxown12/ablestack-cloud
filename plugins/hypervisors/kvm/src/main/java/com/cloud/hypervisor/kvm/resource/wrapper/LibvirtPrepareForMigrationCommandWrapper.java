//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.storage.configdrive.ConfigDrive;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.collections.MapUtils;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.DpdkTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.InterfaceDef.GuestNetType;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;

@ResourceWrapper(handles =  PrepareForMigrationCommand.class)
public final class LibvirtPrepareForMigrationCommandWrapper extends CommandWrapper<PrepareForMigrationCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final PrepareForMigrationCommand command, final LibvirtComputingResource libvirtComputingResource) {
        final VirtualMachineTO vm = command.getVirtualMachine();

        if (command.isRollback()) {
            return handleRollback(command, libvirtComputingResource);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Preparing host for migrating " + vm);
        }

        final NicTO[] nics = vm.getNics();

        Map<String, DpdkTO> dpdkInterfaceMapping = new HashMap<>();

        boolean skipDisconnect = false;

        final KVMStoragePoolManager storagePoolMgr = libvirtComputingResource.getStoragePoolMgr();
        try {
            final LibvirtUtilitiesHelper libvirtUtilitiesHelper = libvirtComputingResource.getLibvirtUtilitiesHelper();

            final Connect conn = libvirtUtilitiesHelper.getConnectionByVmName(vm.getName());

            for (final NicTO nic : nics) {
                LibvirtVMDef.InterfaceDef interfaceDef = libvirtComputingResource.getVifDriver(nic.getType(), nic.getName()).plug(nic, null, "", vm.getExtraConfig());
                if (vm.getDetails() != null) {
                    libvirtComputingResource.setInterfaceDefQueueSettings(vm.getDetails(), vm.getCpus(), interfaceDef);
                }
                if (interfaceDef != null && interfaceDef.getNetType() == GuestNetType.VHOSTUSER) {
                    DpdkTO to = new DpdkTO(interfaceDef.getDpdkOvsPath(), interfaceDef.getDpdkSourcePort(), interfaceDef.getInterfaceMode());
                    dpdkInterfaceMapping.put(nic.getMac(), to);
                }
            }

            /* setup disks, e.g for iso */
            final DiskTO[] volumes = vm.getDisks();
            for (final DiskTO volume : volumes) {
                final DataTO data = volume.getData();

                if (volume.getType() == Volume.Type.ISO) {
                    if (data != null && data.getPath() != null && data.getPath().startsWith(ConfigDrive.CONFIGDRIVEDIR)) {
                        libvirtComputingResource.getVolumePath(conn, volume, vm.isConfigDriveOnHostCache());
                    } else {
                        libvirtComputingResource.getVolumePath(conn, volume);
                    }
                } else {
                    final PrimaryDataStoreTO store = (PrimaryDataStoreTO)data.getDataStore();
                    if (store.getPoolType() == StoragePoolType.RBD) {
                        KVMPhysicalDisk physicalDisk = storagePoolMgr.getPhysicalDisk(store.getPoolType(), store.getUuid(), data.getPath());
                        if(store.getProvider() != null && !store.getProvider().isEmpty() && "ABLESTACK".equals(store.getProvider())){
                            final VolumeObjectTO volumeObject = (VolumeObjectTO)data;
                            String device = libvirtComputingResource.mapRbdDevice(physicalDisk,volumeObject.getKvdoEnable());
                            if (device != null) {
                                logger.debug("RBD device on host is: " + device);
                            } else {
                                throw new InternalErrorException("Error while mapping RBD device on host");
                            }
                        }
                    }
                }

                if (data instanceof VolumeObjectTO) {
                    final VolumeObjectTO volumeObjectTO = (VolumeObjectTO)data;

                    if (volumeObjectTO.requiresEncryption()) {
                        String secretConsumer = volumeObjectTO.getPath();
                        if (volume.getDetails() != null && volume.getDetails().containsKey(DiskTO.SECRET_CONSUMER_DETAIL)) {
                            secretConsumer = volume.getDetails().get(DiskTO.SECRET_CONSUMER_DETAIL);
                        }
                        String secretUuid = libvirtComputingResource.createLibvirtVolumeSecret(conn, secretConsumer, volumeObjectTO.getPassphrase());
                        logger.debug(String.format("Created libvirt secret %s for disk %s", secretUuid, volumeObjectTO.getPath()));
                        volumeObjectTO.clearPassphrase();
                    } else {
                        logger.debug(String.format("disk %s has no passphrase or encryption", volumeObjectTO));
                    }
                }
            }

            skipDisconnect = true;

            if (!storagePoolMgr.connectPhysicalDisksViaVmSpec(vm)) {
                return new PrepareForMigrationAnswer(command, "failed to connect physical disks to host");
            }

            return createPrepareForMigrationAnswer(command, dpdkInterfaceMapping, libvirtComputingResource, vm);
        } catch (final LibvirtException | CloudRuntimeException | InternalErrorException | URISyntaxException e) {
            if (MapUtils.isNotEmpty(dpdkInterfaceMapping)) {
                for (DpdkTO to : dpdkInterfaceMapping.values()) {
                    removeDpdkPort(to.getPort());
                }
            }
            return new PrepareForMigrationAnswer(command, e.toString());
        } finally {
            if (!skipDisconnect) {
                storagePoolMgr.disconnectPhysicalDisksViaVmSpec(vm);
            }
        }
    }

    protected PrepareForMigrationAnswer createPrepareForMigrationAnswer(PrepareForMigrationCommand command, Map<String, DpdkTO> dpdkInterfaceMapping,
                                                                        LibvirtComputingResource libvirtComputingResource, VirtualMachineTO vm) {
        PrepareForMigrationAnswer answer = new PrepareForMigrationAnswer(command);

        if (MapUtils.isNotEmpty(dpdkInterfaceMapping)) {
            logger.debug(String.format("Setting DPDK interface for the migration of VM [%s].", vm));
            answer.setDpdkInterfaceMapping(dpdkInterfaceMapping);
        }

        int newCpuShares = libvirtComputingResource.calculateCpuShares(vm);
        logger.debug(String.format("Setting CPU shares to [%s] for the migration of VM [%s].", newCpuShares, vm));
        answer.setNewVmCpuShares(newCpuShares);

        return answer;
    }

    private Answer handleRollback(PrepareForMigrationCommand command, LibvirtComputingResource libvirtComputingResource) {
        KVMStoragePoolManager storagePoolMgr = libvirtComputingResource.getStoragePoolMgr();
        VirtualMachineTO vmTO = command.getVirtualMachine();

        if (!storagePoolMgr.disconnectPhysicalDisksViaVmSpec(vmTO)) {
            return new PrepareForMigrationAnswer(command, "failed to disconnect physical disks from host");
        }

        return new PrepareForMigrationAnswer(command);
    }
}
