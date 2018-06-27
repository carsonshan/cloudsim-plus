/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */
package org.cloudbus.cloudsim.brokers;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.core.events.SimEvent;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.core.*;
import org.cloudsimplus.autoscaling.VerticalVmScaling;
import org.cloudsimplus.listeners.*;
import org.cloudsimplus.listeners.EventListener;

/**
 * An abstract class to be used as base for implementing a {@link DatacenterBroker}.
 *
 * @author Rodrigo N. Calheiros
 * @author Anton Beloglazov
 * @author Manoel Campos da Silva Filho
 */
public abstract class DatacenterBrokerAbstract extends CloudSimEntity implements DatacenterBroker {
    /**
     * A default {@link Function} which always returns {@link #DEFAULT_VM_DESTRUCTION_DELAY} to indicate that any VM should not be
     * immediately destroyed after it becomes idle.
     * This way, using this Function the broker will destroy VMs only after:
     * <ul>
     * <li>all submitted Cloudlets from all its VMs are finished and there are no waiting Cloudlets;</li>
     * <li>or all running Cloudlets are finished and there are some of them waiting their VMs to be created.</li>
     * </ul>
     *
     * @see #setVmDestructionDelayFunction(Function)
     */
    private static final Function<Vm, Double> DEFAULT_VM_DESTRUCTION_DELAY_FUNCTION = vm -> DEFAULT_VM_DESTRUCTION_DELAY;

    /**
     * A map of registered event listeners for the onVmsCreatedListeners event
     * that the key is the Listener itself and the value indicates if it's a one
     * time listener (which is removed from the list after being notified for the first time).
     *
     * @see #addOnVmsCreatedListener(EventListener)
     * @see #addOneTimeOnVmsCreatedListener(EventListener)
     */
    private final Map<EventListener<DatacenterBrokerEventInfo>, Boolean> onVmsCreatedListeners;

    /**
     * @see #getLastSelectedVm()
     */
    private Vm lastSelectedVm;

    /**
     * The last datacenter where a VM was created or tried to be created.
     */
    private Datacenter lastSelectedDc;

    /**
     * @see #getVmWaitingList()
     */
    private final List<Vm> vmWaitingList;

    /**
     * A map of requests for VM creation sent to Datacenters.
     * The key is a VM and the value is a Datacenter to where
     * a request to create that VM was sent.
     * If the value is null or the VM isn't in the map,
     * it wasn't requested to be created yet.
     */
    private final Map<Vm, Datacenter> vmCreationRequestsMap;

    /**
     * @see #getVmExecList()
     */
    private final List<Vm> vmExecList;

    /**
     * @see #getVmCreatedList()
     */
    private final List<Vm> vmCreatedList;

    /**
     * @see #getCloudletWaitingList()
     */
    private final List<Cloudlet> cloudletWaitingList;

    /**
     * Checks if the last time checked, there were waiting cloudlets or not.
     */
    private boolean wereThereWaitingCloudlets;

    /**
     * A map of requests for Cloudlet creation sent to Datacenters.
     * The key is a Cloudlet and the value is a Datacenter to where
     * a request to create that Cloudlet was sent.
     * If the value is null or the Cloudlet isn't in the map,
     * it wasn't requested to be created yet.
     */
    private final Map<Cloudlet, Datacenter> cloudletCreationRequestsMap;
    private Supplier<Datacenter> datacenterSupplier;
    private Supplier<Datacenter> fallbackDatacenterSupplier;
    private Function<Cloudlet, Vm> vmMapper;

    private Comparator<Vm> vmComparator;
    private Comparator<Cloudlet> cloudletComparator;

    /**
     * @see #getCloudletFinishedList()
     */
    private final List<Cloudlet> cloudletsFinishedList;

    /**
     * @see #getVmCreationRequests()
     */
    private int vmCreationRequests;
    /**
     * @see #getVmCreationAcks()
     */
    private int vmCreationAcks;
    /**
     * @see #getDatacenterList()
     */
    private List<Datacenter> datacenterList;
    /**
     * @see #getDatacenterRequestedList()
     */
    private final Set<Datacenter> datacenterRequestedList;

    private final Map<Vm, Datacenter> vmsToDatacentersMap;
    private Cloudlet lastSubmittedCloudlet;
    private Vm lastSubmittedVm;

    /**
     * @see #getVmDestructionDelayFunction()
     */
    private Function<Vm, Double> vmDestructionDelayFunction;

    /**
     * Creates a DatacenterBroker object.
     *
     * @param simulation the CloudSim instance that represents the simulation the Entity is related to
     */
    public DatacenterBrokerAbstract(final CloudSim simulation) {
        super(simulation);
        this.onVmsCreatedListeners = new HashMap<>();
        this.lastSubmittedCloudlet = Cloudlet.NULL;
        this.lastSubmittedVm = Vm.NULL;
        this.lastSelectedVm = Vm.NULL;
        this.lastSelectedDc = Datacenter.NULL;

        vmCreationRequests = 0;
        vmCreationAcks = 0;

        this.vmWaitingList = new ArrayList<>();
        this.vmExecList = new ArrayList<>();
        this.vmCreatedList = new ArrayList<>();
        this.cloudletWaitingList = new ArrayList<>();
        this.cloudletsFinishedList = new ArrayList<>();

        setDatacenterList(new TreeSet<>());
        datacenterRequestedList = new TreeSet<>();
        vmCreationRequestsMap = new HashMap<>();
        cloudletCreationRequestsMap = new HashMap<>();
        vmsToDatacentersMap = new HashMap<>();

        setDefaultPolicies();

        vmDestructionDelayFunction = DEFAULT_VM_DESTRUCTION_DELAY_FUNCTION;
    }

    /**
     * Sets the default dummy policies for {@link #datacenterSupplier},
     * {@link #fallbackDatacenterSupplier} and {@link #vmMapper}.
     * The actual policies must be set by concrete DatacenterBroker classes.
     */
    private void setDefaultPolicies() {
        datacenterSupplier = () -> Datacenter.NULL;
        fallbackDatacenterSupplier = datacenterSupplier;
        vmMapper = (cloudlet) -> Vm.NULL;
    }

    @Override
    public void submitVmList(final List<? extends Vm> list, final double submissionDelay) {
        setDelayForEntitiesWithNoDelay(list, submissionDelay);
        submitVmList(list);
    }

    /**
     * {@inheritDoc}
     * <p>If the entity already started (the simulation is running),
     * the creation of previously submitted VMs already was requested
     * by the {@link #start()} method that is called just once.
     * By this way, this method will immediately request the creation of these
     * just submitted VMs in order to allow VM creation after
     * the simulation has started. This avoid the developer to
     * dynamically create brokers just to create VMs or Cloudlets during
     * simulation execution.</p>
     *
     * @param list {@inheritDoc}
     */
    @Override
    public void submitVmList(final List<? extends Vm> list) {
        sortVmsIfComparatorIsSet(list);
        setBrokerForEntities(list);
        lastSubmittedVm = setIdForEntitiesWithoutOne(list, lastSubmittedVm);
        vmWaitingList.addAll(list);

        if (isStarted() && !list.isEmpty()) {
            println(String.format(
                "%.2f: %s: List of %d VMs submitted to the broker during simulation execution.\n\t   VMs creation request sent to Datacenter.",
                getSimulation().clock(), getName(), list.size()));
            requestDatacenterToCreateWaitingVms();
        }
    }

    /**
     * Sets the broker for each {@link CustomerEntity} into a given list.
     *
     * @param customerEntities the List of {@link CustomerEntity} to set the broker.
     */
    private void setBrokerForEntities(final List<? extends CustomerEntity> customerEntities) {
        customerEntities.forEach(e -> e.setBroker(this));
    }

    /**
     * Defines IDs for a list of {@link CustomerEntity} entities that don't
     * have one already assigned. Such entities can be a {@link Cloudlet},
     * {@link Vm} or any object that implements {@link CustomerEntity}.
     *
     * @param list list of objects to define an ID
     * @param lastSubmittedEntity the last Entity that was submitted to the broker
     * @return the last Entity in the given List of the lastSubmittedEntity if the List is empty
     */
    private <T extends CustomerEntity> T setIdForEntitiesWithoutOne(final List<? extends T> list, T lastSubmittedEntity){
        return Simulation.setIdForEntitiesWithoutOne(list, lastSubmittedEntity) ? list.get(list.size()-1) : lastSubmittedEntity;
    }

    private void sortVmsIfComparatorIsSet(final List<? extends Vm> list) {
        if(vmComparator != null) {
            list.sort(vmComparator);
        }
    }

    @Override
    public void submitVm(final Vm vm) {
        Objects.requireNonNull(vm);
        if(vm == Vm.NULL){
            return;
        }

        final List<Vm> newList = new ArrayList<>(1);
        newList.add(vm);
        submitVmList(newList);
    }

    @Override
    public void submitCloudlet(final Cloudlet cloudlet) {
        Objects.requireNonNull(cloudlet);
        if(cloudlet == Cloudlet.NULL){
            return;
        }

        final List<Cloudlet> newList = new ArrayList<>(1);
        newList.add(cloudlet);
        submitCloudletList(newList);
    }

    @Override
    public void submitCloudletList(final List<? extends Cloudlet> list, double submissionDelay) {
        submitCloudletList(list, Vm.NULL, submissionDelay);
    }

    @Override
    public void submitCloudletList(final List<? extends Cloudlet> list, Vm vm) {
        submitCloudletList(list, vm, -1);
    }

    @Override
    public void submitCloudletList(final List<? extends Cloudlet> list, Vm vm, double submissionDelay) {
        setDelayForEntitiesWithNoDelay(list, submissionDelay);
        bindCloudletsToVm(list, vm);
        submitCloudletList(list);
    }

    /**
     * {@inheritDoc}
     * <p>If the entity already started (the simulation is running),
     * the creation of previously submitted Cloudlets already was requested
     * by the {@link #start()} method that is called just once.
     * By this way, this method will immediately request the creation of these
     * just submitted Cloudlets if all submitted VMs were already created,
     * in order to allow Cloudlet creation after
     * the simulation has started. This avoid the developer to
     * dynamically create brokers just to create VMs or Cloudlets during
     * simulation execution.</p>
     * @param list {@inheritDoc}
     * @see #submitCloudletList(List, double)
     */
    @Override
    public void submitCloudletList(final List<? extends Cloudlet> list) {
        sortCloudletsIfComparatorIsSet(list);
        setBrokerForEntities(list);
        lastSubmittedCloudlet =  setIdForEntitiesWithoutOne(list, lastSubmittedCloudlet);
        if(list.isEmpty()) {
            return;
        }
        setSimulationForCloudletUtilizationModels(list);
        cloudletWaitingList.addAll(list);
        wereThereWaitingCloudlets = true;

        if (!isStarted()) {
            return;
        }

        println(String.format(
            "%.2f: %s: List of %d Cloudlets submitted to the broker during simulation execution.",
            getSimulation().clock(), getName(), list.size()));

        //If there aren't more VMs to be created, then request Cloudlets creation
        if(vmWaitingList.isEmpty()){
            println(" Cloudlets creation request sent to Datacenter.");
            requestDatacentersToCreateWaitingCloudlets();
            notifyOnVmsCreatedListeners();
        } else
            println(String.format(
                "       Waiting creation of %d VMs to send Cloudlets creation request to Datacenter.",
                vmWaitingList.size()));
    }

    /**
     * Binds a list of Cloudlets to a given {@link Vm}.
     * If the {@link Vm} is {@link Vm#NULL}, the Cloudlets will not be bound.
     *
     * @param cloudlets the List of Cloudlets to be bound to a VM
     * @param vm the VM to bind the Cloudlets to
     */
    private void bindCloudletsToVm(final List<? extends Cloudlet> cloudlets, Vm vm) {
        if(Vm.NULL.equals(vm)){
            return;
        }

        cloudlets.forEach(c -> c.setVm(vm));
    }

    private void sortCloudletsIfComparatorIsSet(final List<? extends Cloudlet> list) {
        if(cloudletComparator != null) {
            list.sort(cloudletComparator);
        }
    }

    private void setSimulationForCloudletUtilizationModels(final List<? extends Cloudlet> list) {
        for(final Cloudlet c: list){
            setSimulationForUtilizationModelIfNotSet(c.getUtilizationModelCpu());
            setSimulationForUtilizationModelIfNotSet(c.getUtilizationModelBw());
            setSimulationForUtilizationModelIfNotSet(c.getUtilizationModelRam());
        }
    }

    private void setSimulationForUtilizationModelIfNotSet(final UtilizationModel cloudletUtilizationModel) {
        if(cloudletUtilizationModel.getSimulation() == null || cloudletUtilizationModel.getSimulation() == Simulation.NULL){
            cloudletUtilizationModel.setSimulation(getSimulation());
        }
    }

    /**
     * Sets the delay for a list of {@link CustomerEntity} entities that don't
     * have a delay already assigned. Such entities can be a {@link Cloudlet},
     * {@link Vm} or any object that implements {@link CustomerEntity}.
     *
     * <p>If the delay is defined as a negative number, objects' delay
     * won't be changed.</p>
     *
     * @param list            list of objects to set their delays
     * @param submissionDelay the submission delay to set
     */
    private void setDelayForEntitiesWithNoDelay(final List<? extends CustomerEntity> list, final double submissionDelay) {
        if(submissionDelay < 0){
            return;
        }

        list.stream()
            .filter(e -> e.getSubmissionDelay() <= 0)
            .forEach(e -> e.setSubmissionDelay(submissionDelay));
    }

    @Override
    public boolean bindCloudletToVm(final Cloudlet cloudlet, final Vm vm) {
        if (!cloudletWaitingList.contains(cloudlet)) {
            return false;
        }

        cloudlet.setVm(vm);
        return true;
    }

    @Override
    public void processEvent(final SimEvent ev) {
        switch (ev.getTag()) {
            case CloudSimTags.DATACENTER_LIST_REQUEST:
                processDatacenterListRequest(ev);
                break;
            case CloudSimTags.VM_CREATE_ACK:
                processVmCreateResponseFromDatacenter(ev);
                break;
            case CloudSimTags.VM_DESTROY:
                requestIdleVmDestruction((Vm)ev.getData());
                break;
            case CloudSimTags.VM_VERTICAL_SCALING:
                requestVmVerticalScaling(ev);
                break;
            case CloudSimTags.CLOUDLET_RETURN:
                processCloudletReturn(ev);
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            default:
                break;
        }
    }

    private void requestVmVerticalScaling(final SimEvent ev) {
        if (!(ev.getData() instanceof VerticalVmScaling)) {
            return;
        }

        final VerticalVmScaling scaling = (VerticalVmScaling) ev.getData();
        getSimulation().sendNow(
            ev.getSource(), scaling.getVm().getHost().getDatacenter(),
            CloudSimTags.VM_VERTICAL_SCALING, scaling);
    }

    /**
     * Process a request to get the list of all Datacenters registered in the
     * Cloud Information Service (CIS) of the {@link #getSimulation() simulation}.
     *
     * @param ev a CloudSimEvent object
     * @pre ev != $null
     */
    private void processDatacenterListRequest(final SimEvent ev) {
        setDatacenterList((Set<Datacenter>) ev.getData());
        println(String.format(
            "\n%.2f: %s: List of %d datacenters(s) received.",
            getSimulation().clock(), getName(), datacenterList.size()));
        requestDatacenterToCreateWaitingVms();
    }

    /**
     * Process the ack received from a Datacenter to a broker's request for
     * creation of a Vm in that Datacenter.
     *
     * @param ev a CloudSimEvent object
     * @return true if the VM was created successfully, false otherwise
     * @pre ev != null
     */
    private boolean processVmCreateResponseFromDatacenter(final SimEvent ev) {
        final Vm vm = (Vm) ev.getData();
        boolean vmCreated = false;
        vmCreationAcks++;

        //if the VM was successfully created in the requested Datacenter
        if (vm.isCreated()) {
            processSuccessVmCreationInDatacenter(vm, vm.getHost().getDatacenter());
            vmCreated = true;
        } else {
            processFailedVmCreationInDatacenter(vm, lastSelectedDc);
        }

        // all the requested VMs have been created
        if (vmWaitingList.isEmpty()) {
            requestDatacentersToCreateWaitingCloudlets();
            notifyOnVmsCreatedListeners();
        } else if (getVmCreationRequests() == getVmCreationAcks()) {
            requestCreationOfWaitingVmsToFallbackDatacenter();
        }

        return vmCreated;
    }

    private void notifyOnVmsCreatedListeners(){
        onVmsCreatedListeners.forEach((key, value) -> key.update(DatacenterBrokerEventInfo.of(key, this)));
        onVmsCreatedListeners.entrySet().removeIf(this::isOneTimeListener);
    }

    /**
     * Checks if an EventListener from the {@link #onVmsCreatedListeners}
     * is a one-time listener, that after being notified for the first time,
     * must be removed from the map of registered listeners.
     *
     * @param eventListenerBooleanEntry the entry for the listener to check
     * @return true if it is a one-time listener, false otherwise
     */
    private boolean isOneTimeListener(final Map.Entry<EventListener<DatacenterBrokerEventInfo>, Boolean> eventListenerBooleanEntry) {
        return eventListenerBooleanEntry.getValue();
    }

    /**
     * After the response (ack) of all VM creation request were received
     * but not all VMs could be created (what means some
     * acks informed about Vm creation failures), try to find
     * another Datacenter to request the creation of the VMs
     * in the waiting list.
     */
    private void requestCreationOfWaitingVmsToFallbackDatacenter() {
        final Datacenter nextDatacenter = fallbackDatacenterSupplier.get();
        lastSelectedDc = nextDatacenter;
        if (nextDatacenter != Datacenter.NULL) {
            clearVmCreationRequestsMapToTryNextDatacenter();
            requestDatacenterToCreateWaitingVms(nextDatacenter, true);
            return;
        }

        /* If it gets here, it means that all datacenters were already queried
         * and not all VMs could be created, but some of them could. */
        if (vmExecList.isEmpty()) {
            println(String.format("%.2f: %s: %s", getSimulation().clock(), getName(),
                "none of the required VMs could be created. Aborting"));
            shutdownEntity();
            return;
        }

        requestDatacentersToCreateWaitingCloudlets();
    }

    /**
     * After trying to create the waiting VMs at a given Datacenter
     * and not all VMs could be created, removes the VMs yet waiting
     * in order to allow requesting their creation in another datacenter.
     * If a waiting VM is inside the {@link #vmCreationRequestsMap},
     * it means that it was already sent a request to create it.
     * Removing it from such a map, will allow
     * to trying creating the VM at another Datacenter.
     */
    private void clearVmCreationRequestsMapToTryNextDatacenter() {
        for (final Vm vm : vmWaitingList) {
            vmCreationRequestsMap.remove(vm);
        }
    }

    /**
     * Process a response from a Datacenter informing that it was able to
     * create the VM requested by the broker.
     *
     * @param vm         id of the Vm that succeeded to be created inside the Datacenter
     * @param datacenter id of the Datacenter where the request to create
     *                   the Vm succeeded
     */
    private void processSuccessVmCreationInDatacenter(final Vm vm, final Datacenter datacenter) {
        vmsToDatacentersMap.put(vm, datacenter);
        vmWaitingList.remove(vm);
        vmExecList.add(vm);
        vmCreatedList.add(vm);
    }

    /**
     * Process a response from a Datacenter informing that it was NOT able to
     * create the VM requested by the broker.
     *
     * @param vm         id of the Vm that failed to be created inside the Datacenter
     * @param datacenter id of the Datacenter where the request to create
     */
    private void processFailedVmCreationInDatacenter(final Vm vm, final Datacenter datacenter) {
        vm.notifyOnCreationFailureListeners(datacenter);
        println(String.format(
            "%.2f: %s: Creation of %s failed in %s",
            getSimulation().clock(), getName(), vm, datacenter));
    }

    /**
     * Processes the end of execution of a given cloudlet inside a Vm.
     *
     * @param ev the cloudlet that has just finished to execute and was returned to the broker
     */
    private void processCloudletReturn(final SimEvent ev) {
        final Cloudlet c = (Cloudlet) ev.getData();
        cloudletsFinishedList.add(c);
        println(String.format("%.2f: %s: %s %d finished and returned to broker.",
            getSimulation().clock(), getName(), c.getClass().getSimpleName(), c.getId()));

        if(c.getVm().getCloudletScheduler().isEmpty()){
            requestIdleVmDestruction(c.getVm());
            return;
        }

        requestVmDestructionAfterAllCloudletsFinished();
    }

    /**
     * Request the destruction of VMs after all running cloudlets have finished and returned to the broker.
     * If there is no waiting Cloudlet, request all VMs to be destroyed.
     */
    private void requestVmDestructionAfterAllCloudletsFinished() {
        for (int i = vmExecList.size()-1; i >= 0; i--) {
            requestIdleVmDestruction(vmExecList.get(i));
        }


        if (cloudletWaitingList.isEmpty()) {
            return;
        }

        /*
        There are some cloudlets waiting their VMs to be created.
        Idle VMs were destroyed above and here it requests the creation of waiting ones.
        When there is waiting Cloudlets, the destruction
        of idle VMs possibly free resources to start waiting VMs.
        This way, if a VM destruction delay function is not set,
        it defines one that always return 0 to indicate
        idle VMs must be destroyed immediately.
        */
        requestDatacenterToCreateWaitingVms();
    }

    /**
     * Request an idle VM to be destroyed at the time defined by a delay {@link Function}.
     * The request will be sent if the given delay function returns a value
     * greater than {@link #DEFAULT_VM_DESTRUCTION_DELAY}.
     * Otherwise, it doesn't send the request, meaning the VM should not be destroyed according to a specific delay.
     *
     * @param vm the VM to destroy
     * @see #getVmDestructionDelayFunction()
     */
    private void requestIdleVmDestruction(final Vm vm) {
        final double delay = vmDestructionDelayFunction.apply(vm);

        boolean vmAlive = vmExecList.contains(vm);
        if(vmAlive && ((delay > DEFAULT_VM_DESTRUCTION_DELAY && vm.isIdleEnough(delay)) || isFinished())) {
            println(String.format("%.2f: %s: Requesting %s destruction.", getSimulation().clock(), getName(), vm));
            sendNow(getDatacenter(vm), CloudSimTags.VM_DESTROY, vm);
            vmExecList.remove(vm);
            vmAlive = false;
        }

        if (isTimeToShutdownBroker() && isBrokerIdle()) {
            shutdownEntity();
            return;
        }

        if(vmAlive && delay > DEFAULT_VM_DESTRUCTION_DELAY){
            send(this, getDelayToCheckVmIdleness(vm), CloudSimTags.VM_DESTROY, vm);
        }

    }

    /**
     * Gets the time to wait to check again if a VM became idle,
     * based on the {@link #vmDestructionDelayFunction}.
     *
     * @param vm the VM to get the delay to check it idleness again
     * @return the delay to check the VM idleness again
     */
    private double getDelayToCheckVmIdleness(final Vm vm) {
        /*
         * If the delay or the schedulingInterval is not set
         * or is zero, gets the minTimeBetweenEvents, since
         * the delay to check VM idleness again has to be greater than zero.
         */
        final double schedulingInterval = vm.getHost().getDatacenter().getSchedulingInterval();
        final double delay = vmDestructionDelayFunction.apply(vm);

        if(delay <= 0 && schedulingInterval <= 0){
            return getSimulation().getMinTimeBetweenEvents();
        }
        else if(delay <= 0) { // if just the delay is not a positive number
            return schedulingInterval;
        }

        return Math.min(Math.abs(delay), Math.abs(schedulingInterval));
    }

    private boolean isBrokerIdle() {
        return cloudletWaitingList.isEmpty() && vmWaitingList.isEmpty() && vmExecList.isEmpty();
    }

    private boolean isTimeToShutdownBroker() {
        return isAlive() &&
              (!getSimulation().isTerminationTimeSet() || getSimulation().isTimeToTerminateSimulationUnderRequest());
    }

    /**
     * Request the creation of VMs in the
     * {@link #getVmWaitingList() VM waiting list}
     * inside some Datacenter.
     *
     * @see #submitVmList(java.util.List)
     */
    protected void requestDatacenterToCreateWaitingVms() {
        lastSelectedDc = Datacenter.NULL.equals(lastSelectedDc) ? datacenterSupplier.get() : lastSelectedDc;
        requestDatacenterToCreateWaitingVms(lastSelectedDc);
    }

    /**
     * Request a specific Datacenter to create the VM in the
     * {@link #getVmWaitingList() VM waiting list}.
     *
     * @param datacenter id of the Datacenter to request the VMs creation
     * @see #submitVmList(java.util.List)
     */
    protected void requestDatacenterToCreateWaitingVms(final Datacenter datacenter) {
        requestDatacenterToCreateWaitingVms(datacenter, false);
    }

    /**
     * Request a specific Datacenter to create the VM in the
     * {@link #getVmWaitingList() VM waiting list}.
     *
     * @param datacenter id of the Datacenter to request the VMs creation
     * @param isFallbackDatacenter true to indicate that the given Datacenter is a fallback one,
     *                             i.e., it's a next Datacenter where the creation of VMs is being tried
     *                             (after some VMs could not be created into the previous Datacenter);
     *                             false to indicate that this is a regular Datacenter where
     *                             VM creation has to be tried.
     * @see #submitVmList(java.util.List)
     */
    protected void requestDatacenterToCreateWaitingVms(final Datacenter datacenter, final boolean isFallbackDatacenter) {
        int requestedVms = 0;
        final String fallbackMsg = isFallbackDatacenter ? " (due to lack of a suitable Host in previous one)" : "";
        for (final Vm vm :vmWaitingList) {
            if (!vmsToDatacentersMap.containsKey(vm) && !vmCreationRequestsMap.containsKey(vm)) {
                println(String.format(
                    "%.2f: %s: Trying to Create %s in %s%s",
                    getSimulation().clock(), getName(), vm, datacenter.getName(), fallbackMsg));
                sendNow(datacenter, CloudSimTags.VM_CREATE_ACK, vm);
                vmCreationRequestsMap.put(vm, datacenter);
                requestedVms++;
            }
        }
        datacenterRequestedList.add(datacenter);
        this.vmCreationRequests += requestedVms;
    }

    /**
     * <p>Request Datacenters to create the Cloudlets in the
     * {@link #getCloudletWaitingList() Cloudlets waiting list}.
     * If there aren't available VMs to host all cloudlets,
     * the creation of some ones will be postponed.</p>
     *
     * <p>This method is called after all submitted VMs are created
     * in some Datacenter.</p>
     *
     * @see #submitCloudletList(java.util.List)
     */
    protected void requestDatacentersToCreateWaitingCloudlets() {
        /* @todo @author manoelcampos Where is checked if the Vm where
         * a cloudlet was submitted to has the required resources?
         * See https://github.com/manoelcampos/cloudsim-plus/issues/126
         */

        final List<Cloudlet> successfullySubmitted = new ArrayList<>();
        for (final Cloudlet cloudlet : cloudletWaitingList) {
            if (cloudletCreationRequestsMap.containsKey(cloudlet)) {
                continue;
            }

            //selects a VM for the given Cloudlet
            lastSelectedVm = vmMapper.apply(cloudlet);
            if (lastSelectedVm == Vm.NULL) {
                // vm was not created
                println(String.format(
                    "%.2f: %s: : Postponing execution of %s: bind VM not available.",
                    getSimulation().clock(), getName(), cloudlet));
                continue;
            }

            final String delayStr =
                cloudlet.getSubmissionDelay() > 0 ?
                    String.format(" with a requested delay of %.0f seconds", cloudlet.getSubmissionDelay()) :
                    "";
            println(String.format(
                "%.2f: %s: Sending %s to %s in %s%s.",
                getSimulation().clock(), getName(), cloudlet,
                lastSelectedVm, lastSelectedVm.getHost(), delayStr));
            cloudlet.setVm(lastSelectedVm);
            send(getDatacenter(lastSelectedVm),
                cloudlet.getSubmissionDelay(), CloudSimTags.CLOUDLET_SUBMIT, cloudlet);
            cloudletCreationRequestsMap.put(cloudlet, getDatacenter(lastSelectedVm));
            successfullySubmitted.add(cloudlet);
        }

        cloudletWaitingList.removeAll(successfullySubmitted);
        allWaitingCloudletsSubmittedToVm();
    }

    private boolean allWaitingCloudletsSubmittedToVm() {
        if(!cloudletWaitingList.isEmpty()){
            return false;
        }

        //avoid duplicated notifications
        if(wereThereWaitingCloudlets){
            println(String.format(
                "%.2f: %s: All waiting Cloudlets submitted to some VM.",
                getSimulation().clock(), getName()));
            wereThereWaitingCloudlets = false;
        }

        return true;
    }

    @Override
    public void shutdownEntity() {
        super.shutdownEntity();
        println(String.format("%.2f: %s is shutting down...", getSimulation().clock(), getName()));
        requestVmDestructionAfterAllCloudletsFinished();
    }

    @Override
    public void startEntity() {
        println(String.format("%s is starting...", getName()));
        schedule(getSimulation().getCloudInfoService(), 0, CloudSimTags.DATACENTER_LIST_REQUEST);
    }

    @Override
    public <T extends Vm> List<T> getVmCreatedList() {
        return (List<T>) vmCreatedList;
    }

    @Override
    public <T extends Vm> List<T> getVmExecList() {
        return (List<T>) vmExecList;
    }

    @Override
    public <T extends Vm> List<T> getVmWaitingList() {
        return (List<T>) vmWaitingList;
    }

    @Override
    public Vm getWaitingVm(final int index) {
        if (index >= 0 && index < vmWaitingList.size()) {
            return vmWaitingList.get(index);
        }
        return Vm.NULL;
    }

    @Override
    public Set<Cloudlet> getCloudletCreatedList() {
        return cloudletCreationRequestsMap.keySet();
    }

    @Override
    public <T extends Cloudlet> List<T> getCloudletWaitingList() {
        return (List<T>) cloudletWaitingList;
    }

    @Override
    public <T extends Cloudlet> List<T> getCloudletFinishedList() {
        return (List<T>) new ArrayList<>(cloudletsFinishedList);
    }

    /**
     * Gets a Vm at a given index from the {@link #getVmExecList() list of created VMs}.
     *
     * @param vmIndex the index where a VM has to be got from the created VM list
     * @return the VM at the given index or {@link Vm#NULL} if the index is invalid
     */
    protected Vm getVmFromCreatedList(final int vmIndex) {
        return vmIndex >= 0 && vmIndex < vmExecList.size() ? vmExecList.get(vmIndex) : Vm.NULL;
    }

    /**
     * Gets the number of VM creation requests.
     *
     * @return the number of VM creation requests
     */
    protected int getVmCreationRequests() {
        return vmCreationRequests;
    }

    /**
     * Gets the number of acknowledges (ACKs) received from Datacenters
     * in response to requests to create VMs.
     * The number of acks doesn't mean the number of created VMs,
     * once Datacenters can respond informing that a Vm could not be created.
     *
     * @return the number vm creation acks
     */
    protected int getVmCreationAcks() {
        return vmCreationAcks;
    }

    /**
     * Gets the list of available datacenters.
     *
     * @return the dc list
     */
    protected List<Datacenter> getDatacenterList() {
        return datacenterList;
    }

    /**
     * Sets the list of available datacenters.
     *
     * @param datacenterList the new dc list
     */
    protected final void setDatacenterList(Set<Datacenter> datacenterList) {
        this.datacenterList = new ArrayList<>(datacenterList);
    }

    /**
     * Gets the Datacenter where a VM is placed.
     *
     * @param vm the VM to get its Datacenter
     * @return
     */
    protected Datacenter getDatacenter(final Vm vm) {
        return vmsToDatacentersMap.get(vm);
    }

    /**
     * Gets the list of datacenters where was requested to place VMs.
     *
     * @return
     */
    protected Set<Datacenter> getDatacenterRequestedList() {
        return datacenterRequestedList;
    }

    /**
     * @return latest VM selected to run a cloudlet.
     */
    protected Vm getLastSelectedVm() {
        return lastSelectedVm;
    }

    @Override
    public final void setDatacenterSupplier(final Supplier<Datacenter> datacenterSupplier) {
        Objects.requireNonNull(datacenterSupplier);
        this.datacenterSupplier = datacenterSupplier;
    }

    @Override
    public final void setFallbackDatacenterSupplier(final Supplier<Datacenter> fallbackDatacenterSupplier) {
        Objects.requireNonNull(fallbackDatacenterSupplier);
        this.fallbackDatacenterSupplier = fallbackDatacenterSupplier;
    }

    @Override
    public final void setVmMapper(final Function<Cloudlet, Vm> vmMapper) {
        Objects.requireNonNull(vmMapper);
        this.vmMapper = vmMapper;
    }

    @Override
    public void setVmComparator(final Comparator<Vm> comparator) {
        this.vmComparator = comparator;
    }

    @Override
    public void setCloudletComparator(final Comparator<Cloudlet> comparator) {
        this.cloudletComparator = comparator;
    }

    @Override
    public DatacenterBroker addOnVmsCreatedListener(final EventListener<DatacenterBrokerEventInfo> listener) {
        return addOneTimeOnCreationOfWaitingVmsFinishListener(listener, false);
    }

    @Override
    public DatacenterBroker addOneTimeOnVmsCreatedListener(final EventListener<DatacenterBrokerEventInfo> listener) {
        return addOneTimeOnCreationOfWaitingVmsFinishListener(listener, true);
    }

    public DatacenterBroker addOneTimeOnCreationOfWaitingVmsFinishListener(final EventListener<DatacenterBrokerEventInfo> listener, final Boolean oneTimeListener) {
        Objects.requireNonNull(listener);
        this.onVmsCreatedListeners.put(listener, oneTimeListener);
        return this;
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public Function<Vm, Double> getVmDestructionDelayFunction() {
        return vmDestructionDelayFunction;
    }

    @Override
    public DatacenterBroker setVmDestructionDelayFunction(final Function<Vm, Double> function) {
        this.vmDestructionDelayFunction = function == null ? DEFAULT_VM_DESTRUCTION_DELAY_FUNCTION : function;
        return this;
    }

    @Override
    public boolean isThereWaitingCloudlets() {
        return !cloudletWaitingList.isEmpty();
    }
}
