/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.cloudsimplus.migration;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import static java.util.Comparator.comparingDouble;
import java.util.List;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterCharacteristics;
import org.cloudbus.cloudsim.datacenters.DatacenterCharacteristicsSimple;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.distributions.ContinuousDistribution;
import org.cloudbus.cloudsim.distributions.UniformDistr;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.ResourceProvisioner;
import org.cloudbus.cloudsim.provisioners.ResourceProvisionerSimple;
import org.cloudbus.cloudsim.resources.Bandwidth;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.resources.Ram;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmScheduler;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerSpaceShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.util.Log;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.autoscaling.HorizontalVmScalingSimple;
import org.cloudsimplus.autoscaling.VmScaling;
import org.cloudsimplus.builders.tables.CloudletsTableBuilderHelper;
import org.cloudsimplus.listeners.EventInfo;

/**
 * An example that creates VMs dinamically in runtime.
 *
 * @author raysaoliveira
 */
public class DynamicVmCreationByResponseTimeExample {

    private static final int SCHEDULING_INTERVAL = 5;

    /**
     * The interval to request the creation of new Cloudlets.
     */
    private static final int CLOUDLETS_CREATION_INTERVAL = SCHEDULING_INTERVAL * 2;

    private static final int HOSTS = 50;
    private static final int HOST_PES = 32;
    private static final int VMS = 4;
    private static final int CLOUDLETS = 6;
    private final CloudSim simulation;
    private DatacenterBroker broker0;
    private List<Host> hostList;
    private List<Vm> vmList;
    private List<Cloudlet> cloudletList;

    /**
     * Different lengths that will be randomly assigned to created Cloudlets.
     */
    private static final long[] CLOUDLET_LENGTHS = {2000, 4000, 10000, 16000, 2000, 30000, 20000};
    private ContinuousDistribution rand;

    private int createdCloudlets;
    private int createsVms;

    private double responseTimeCloudlet;
 
    public static void main(String[] args) throws FileNotFoundException, IOException {
        Log.printFormattedLine(" Starting... ");
        new DynamicVmCreationByResponseTimeExample();
    }

    public DynamicVmCreationByResponseTimeExample() throws FileNotFoundException, IOException {

        final long seed = 1;
        rand = new UniformDistr(0, CLOUDLET_LENGTHS.length, seed);
        hostList = new ArrayList<>(HOSTS);
        vmList = new ArrayList<>(VMS);
        cloudletList = new ArrayList<>(CLOUDLETS);

        simulation = new CloudSim();

        simulation.addOnClockTickListener(this::createNewCloudlets);

        createDatacenter();
        broker0 = new DatacenterBrokerSimple(simulation);

        vmList.addAll(createListOfScalableVms(VMS));

        createCloudletList();
        broker0.submitVmList(vmList);
        broker0.submitCloudletList(cloudletList);

        simulation.start();

        System.out.println("________________________________________________________________");
        System.out.println("\n\t\t - System Metrics - \n ");

        //responseTime
        responseTimeCloudlet = responseTimeCloudlet(cloudletList);
        System.out.printf("\t** Response Time of Cloudlets %.2f %n", responseTimeCloudlet);

        printSimulationResults();
    }

    /**
     * Creates new Cloudlets at every 10 seconds up to the 50th simulation
     * second. A reference to this method is set as the {@link EventListener} to
     * the {@link Simulation#addOnClockTickListener(EventListener)}. The method
     * is then called every time the simulation clock advances.
     *
     * @param eventInfo the information about the OnClockTick event that has
     * happened
     */
    private void createNewCloudlets(EventInfo eventInfo) {
        final long time = (long) eventInfo.getTime();
        if (time % CLOUDLETS_CREATION_INTERVAL == 0 && time <= 50) {
            final int numberOfCloudlets = 4;
            Log.printFormattedLine("\t#Creating %d Cloudlets at time %d.", numberOfCloudlets, time);
            List<Cloudlet> newCloudlets = new ArrayList<>(numberOfCloudlets);
            for (int i = 0; i < numberOfCloudlets; i++) {
                Cloudlet cloudlet = createCloudlet();
                cloudletList.add(cloudlet);
                newCloudlets.add(cloudlet);
            }

            broker0.submitCloudletList(newCloudlets);
        }
    }

    private Cloudlet createCloudlet() {
        final int id = createdCloudlets++;
        //randomly selects a length for the cloudlet
        final long length = CLOUDLET_LENGTHS[(int) rand.sample()];
        UtilizationModel utilization = new UtilizationModelFull();
        return new CloudletSimple(id, length, 2)
                .setFileSize(1024)
                .setOutputSize(1024)
                .setUtilizationModel(utilization)
                .setBroker(broker0);
    }

    private void createCloudletList() {
        for (int i = 0; i < CLOUDLETS; i++) {
            cloudletList.add(createCloudlet());
        }
    }

    /**
     * Creates a Datacenter and its Hosts.
     */
    private void createDatacenter() {
        for (int i = 0; i < HOSTS; i++) {
            hostList.add(createHost());
        }

        DatacenterCharacteristics characteristics = new DatacenterCharacteristicsSimple(hostList);
        Datacenter dc0 = new DatacenterSimple(simulation, characteristics, new VmAllocationPolicySimple());
        dc0.setSchedulingInterval(SCHEDULING_INTERVAL);
    }

    private Host createHost() {
        List<Pe> pesList = new ArrayList<>(HOST_PES);
        for (int i = 0; i < HOST_PES; i++) {
            pesList.add(new PeSimple(i, new PeProvisionerSimple(1000)));
        }

        ResourceProvisioner ramProvisioner = new ResourceProvisionerSimple(new Ram(20480));
        ResourceProvisioner bwProvisioner = new ResourceProvisionerSimple(new Bandwidth(100000));
        VmScheduler vmScheduler = new VmSchedulerTimeShared();
        final int id = hostList.size();
        return new HostSimple(id, 10000000, pesList)
                .setRamProvisioner(ramProvisioner)
                .setBwProvisioner(bwProvisioner)
                .setVmScheduler(vmScheduler);
    }

    /**
     * Creates a list of initial VMs in which each VM is able to scale
     * horizontally when it is overloaded.
     *
     * @param numberOfVms number of VMs to create
     * @return the list of scalable VMs
     * @see #createHorizontalVmScaling(Vm)
     */
    private List<Vm> createListOfScalableVms(final int numberOfVms) {
        List<Vm> newList = new ArrayList<>(numberOfVms);
        for (int i = 0; i < numberOfVms; i++) {
            Vm vm = createVm();
            createHorizontalVmScaling(vm);
            newList.add(vm);
        }

        return newList;
    }

    /**
     * Creates a Vm object.
     *
     * @return the created Vm
     */
    private Vm createVm() {
        final int id = createsVms++;
        Vm vm = new VmSimple(id, 1000, 2)
                .setRam(512).setBw(1000).setSize(10000).setBroker(broker0)
                .setCloudletScheduler(new CloudletSchedulerTimeShared());

        return vm;
    }

    /**
     * Creates a {@link HorizontalVmScaling} object for a given VM.
     *
     * @param vm the VM in which the Horizontal Scaling will be created
     * @see #createListOfScalableVms(int)
     */
    private void createHorizontalVmScaling(Vm vm) {
        VmScaling horizontalScaling = new HorizontalVmScalingSimple();
        horizontalScaling
                .setOverloadPredicate(this::isVmOverloaded)
                .setVmSupplier(this::createVm);
        vm.setHorizontalScaling(horizontalScaling);
    }

    /**
     * A {@link Predicate} that checks if a given VM is overloaded or not based
     * on upper CPU utilization threshold. A reference to this method is
     * assigned to each Horizontal VM Scaling created.
     *
     * @param vm the VM to check if it is overloaded
     * @return true if the VM is overloaded, false otherwise
     * @see #createHorizontalVmScaling(Vm)
     */
    private boolean isVmOverloaded(Vm vm) {
        return vm.getTotalUtilizationOfCpu() > 0.7;
    }
    
    private void printSimulationResults() {
        List<Cloudlet> finishedCloudlets = broker0.getCloudletsFinishedList();
        Comparator<Cloudlet> sortByVmId = comparingDouble(c -> c.getVm().getId());
        Comparator<Cloudlet> sortByStartTime = comparingDouble(c -> c.getExecStartTime());
        finishedCloudlets.sort(sortByVmId.thenComparing(sortByStartTime));

        new CloudletsTableBuilderHelper(finishedCloudlets).build();
    }


    /**
     * Shows the response time of cloudlets
     *
     * @param cloudlets to calculate the response time
     * @return responseTimeCloudlet
     */
    private double responseTimeCloudlet(List<Cloudlet> cloudlets) {
        double responseTime = 0;
        for (Cloudlet cloudlet : cloudlets) {
            responseTime = cloudlet.getFinishTime() - cloudlet.getLastDatacenterArrivalTime();
        }
        return responseTime;

    }

    /**
     * @return the responseTimeCloudlet
     */
    public double getResponseTime() {
        return responseTimeCloudlet;
    }
}