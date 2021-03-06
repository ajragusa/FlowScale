/** 
 * Copyright 2012 InCNTRE, This file is released under Apache 2.0 license except for component libraries under different licenses
http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.iu.incntre.flowscale;

import grnoc.net.util.ipaddress.IPv4Address;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.beaconcontroller.core.IOFSwitch;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.HexString;
import org.openflow.util.U16;

/**
 * @author Ali Khalfan (akhalfan@indiana.edu)
 */

public class Group {

	private String[] values;
	private int groupId;
	private String groupName;
	private List<Short> inputPorts = new ArrayList<Short>();
	private List<Short> outputPorts = new ArrayList<Short>();
	private ArrayList<Short> outputPortsUp = new ArrayList<Short>();
	private short priority;
	private long inputSwitchDatapathId;
	private long outputSwitchDatapathId;
	public static final short ETHERTYPE_IP = 0X800;
	public static final byte NWTYPE_TCP = 6;

	public static final int IP_TYPE = 1;
	public static final int TRANSPORT_TYPE = 2;
	public static final int ETHERNET_TYPE = 3;
	private int type;
	private int maximumFlowsAllowed;
	private boolean dropPortGroup = false;
	private FlowscaleController flowscaleController;
	private byte transportDirection;
	private byte networkProtocol;
	private int portCounter = 0;
	HashMap<Integer, Integer> mirrorMapper = new HashMap<Integer, Integer>();
	private ArrayList<OFRule> groupRules = new ArrayList<OFRule>();

	public Group(FlowscaleController controller) {

		this.flowscaleController = controller;

	}

	public List<OFRule> getGroupRules() {

		return this.groupRules;

	}

	/**
	 * this method is invoked everytime a switch connects to the controller and
	 * the switch is registred with the controller , it will update the list of
	 * ports and push the default rules to the switch plus the group rules
	 * 
	 * @param sw
	 * @throws Exception
	 */
	public void switchUpAlert(IOFSwitch sw) throws Exception {

		List<OFPhysicalPort> physicalPorts = sw.getFeaturesReply().getPorts();

		for (OFPhysicalPort ofp : physicalPorts) {

			if (ofp.getState() % 2 == 0
					&& this.outputPorts.contains(ofp.getPortNumber())) {
				FlowscaleController.logger.debug("added port {}",
						ofp.getPortNumber());

				if (!(outputPortsUp.contains(new Short(ofp.getPortNumber())))) {
					outputPortsUp.add(ofp.getPortNumber());
				}

			}

		}

		FlowscaleController.logger.debug("output ports up after adding is {}",
				outputPortsUp.size());

		pushRules();
	}

	/**
	 * clear all group rules associated with this switch
	 * 
	 * @param sw
	 */
	public void switchDownAlert(IOFSwitch sw) {

		this.groupRules.clear();

	}

	/**
	 * This method will add the group to the list of group objects, method will
	 * be invoked when a group is added from the web user interface or when
	 * FlowscaleController is instantiated and retrieves the details from the
	 * database
	 * 
	 * @param groupIdString
	 * @param groupName
	 * @param inputSwitchDatapathIdString
	 * @param outputSwitchDatapathIdString
	 * @param inputPortListString
	 * @param outputPortListString
	 * @param typeString
	 * @param priorityString
	 * @param valuesString
	 * @param maximumFlowsAllowedString
	 * @param networkProtocolString
	 * @param transportDirectionString
	 */

	public void addGroupDetails(String groupIdString, String groupName,
			String inputSwitchDatapathIdString,
			String outputSwitchDatapathIdString, String inputPortListString,
			String outputPortListString, String typeString,
			String priorityString, String valuesString,
			String maximumFlowsAllowedString, String networkProtocolString,
			String transportDirectionString) {

		try {
			groupId = Integer.parseInt(groupIdString);
			FlowscaleController.logger.info("group ID added {}", groupId);
		} catch (NumberFormatException nfe) {
			FlowscaleController.logger.error("{}", nfe);
			return;
		}

		this.groupName = groupName;

		inputSwitchDatapathId = HexString.toLong(inputSwitchDatapathIdString);
		outputSwitchDatapathId = HexString.toLong(outputSwitchDatapathIdString);

		if (outputPortListString.equals("")) {

			dropPortGroup = true;

		} else {

			String[] outputPortsString = outputPortListString.split(",");
			FlowscaleController.logger.debug(
					"output ports before adding group arre {}",
					outputPortListString);
			for (String s : outputPortsString) {
				try {
					if (!(outputPorts.contains(new Short(Short.parseShort(s))))) {
						outputPorts.add(Short.parseShort(s));
					}
				} catch (NumberFormatException numbe) {
					FlowscaleController.logger.error("{}", numbe);
					continue;
				}
			}

		}

		type = Integer.parseInt(typeString);
		maximumFlowsAllowed = Integer.parseInt(maximumFlowsAllowedString);

		if (type == TRANSPORT_TYPE) {
			this.networkProtocol = Byte.parseByte(networkProtocolString);
			this.transportDirection = Byte.parseByte(transportDirectionString);

		}

		priority = Short.parseShort(priorityString);

		values = valuesString.split(",");

		// get ports that are up

		// populate ports

		List<OFPhysicalPort> physicals = flowscaleController.getSwitchDevices()
				.get(this.outputSwitchDatapathId).getPortStates();

		if (physicals != null) {

			for (OFPhysicalPort physical : physicals) {
				if (physical.getState() % 2 == 0
						&& outputPorts.contains(physical.getPortNumber()))

					if (!(outputPortsUp.contains(new Short(physical
							.getPortNumber())))) {
						outputPortsUp.add(physical.getPortNumber());
					}

			}

			FlowscaleController.logger.debug("ports up in this group are {}",
					outputPortsUp);
		}

		FlowscaleController.logger.debug("ports up are {}", this.outputPortsUp);

	}

	/**
	 * This method is called by pushRules()
	 * 
	 * @see pushRules()
	 * @throws ArithmeticException
	 */
	private void generateRules() throws ArithmeticException {
		FlowscaleController.logger.trace("in generating rules with type {}",
				type);
		switch (type) {

		case IP_TYPE:
			try {
				generateIPRules();

			} catch (ArithmeticException ae) {
				throw ae;
			}

			break;

		case TRANSPORT_TYPE:

			generateTransportPortRules(networkProtocol, transportDirection);
			break;

		case ETHERNET_TYPE:
			generateEtherRules();
			break;

		}

		// generate rules from values given
	}

	/**
	 * This private method is called whenever the group inserted is an IP rule
	 * (usually the majority of rules), it break the IP subnets in chunks based
	 * on how many flows are specified , current method of assigning chunks of
	 * smaller IP subnets to ports is being done in a round-robin fashion , it
	 * is possible to change this to random assignment creating a random integer
	 * and retrieving a port from the ouputPortsUp list using that random index
	 * 
	 * @throws ArithmeticException
	 */
	private void generateIPRules() throws ArithmeticException {

		FlowscaleController.logger.debug(" up ports are {}", outputPortsUp);

		int flowForEachValue = (this.maximumFlowsAllowed / values.length);
		ArrayList<IPAddress> ipAddressValues = null;

		for (String s : values) {

			String[] ipAndSubnet = s.split("/");

			ipAddressValues = generateIPandSubnets(ipAndSubnet,
					flowForEachValue / 2);

			int actionPort = 0;
			
			int portCounter = 0;
			for (IPAddress ipAddress : ipAddressValues) {

				if (this.dropPortGroup) {
					actionPort = -1;
				} else {
					try {

						actionPort = this.outputPortsUp.get(portCounter
								% outputPortsUp.size());
						portCounter = portCounter + 1;

					} catch (ArithmeticException ae) {
						throw new ArithmeticException();
					} catch (IllegalArgumentException iae) {
						FlowscaleController.logger
								.info("No ports are up for this group exiting...");
						continue;
					}
				}

				// set source rule

				short rulePriority =0;
				
				if(this.priority == 100){
				rulePriority = flowscaleController.getSwitchDevices().
				get(this.outputSwitchDatapathId).getSwitchPriority();
				rulePriority = (short)(rulePriority +1);
				flowscaleController.getSwitchDevices().
				get(this.outputSwitchDatapathId).setSwitchPriority(rulePriority);
				}else{
					rulePriority = this.priority;
					
				}
				
				OFRule ofRuleSource = new OFRule();
				OFMatch ofMatchSource = new OFMatch();
				ofMatchSource.setDataLayerType((short) 0x0800);

				ofMatchSource.setNetworkSource(ipAddress.getIpAddressValue());

				short maskingBits = (short) (ipAddress.getSubnet() - 1);
				int wildCardSource = OFMatch.OFPFW_ALL ^ OFMatch.OFPFW_DL_TYPE
						^ OFMatch.OFPFW_NW_SRC_ALL
						^ (maskingBits << OFMatch.OFPFW_NW_SRC_SHIFT);

				ofMatchSource.setWildcards(wildCardSource);

				ofRuleSource.setMatch(ofMatchSource);
				ofRuleSource.setPriority(rulePriority);

				ofRuleSource.setPort(actionPort);

				FlowscaleController.logger.debug(
						"ip address match is {} and masking bit is {} ",
						ipAddress.getIpAddressValue(), ipAddress.getSubnet());

				FlowscaleController.logger.debug(
						"match is {} and wildcard is {}",
						ofMatchSource.toString(), wildCardSource);

				this.groupRules.add(ofRuleSource);

				// end set source rule

				// set destination rule:
				OFRule ofRuleDestination = new OFRule();
				OFMatch ofMatchDestination = new OFMatch();

				ofMatchDestination.setDataLayerType((short) 0x0800);
				ofMatchDestination.setNetworkDestination(ipAddress
						.getIpAddressValue());

				int wildCardDestination = OFMatch.OFPFW_ALL
						^ OFMatch.OFPFW_DL_TYPE ^ OFMatch.OFPFW_NW_DST_ALL
						^ (maskingBits << OFMatch.OFPFW_NW_DST_SHIFT);

				ofMatchDestination.setWildcards(wildCardDestination);

				ofRuleDestination.setMatch(ofMatchDestination);
				ofRuleDestination.setPriority(rulePriority);
				ofRuleDestination.setPort(actionPort);

				FlowscaleController.logger.debug(
						"ip address match is {} and masking bit is {} ",
						ipAddress.getIpAddressValue(), ipAddress.getSubnet());

				FlowscaleController.logger.debug(
						"match is {} and wildcard is {}",
						ofMatchDestination.toString(), wildCardDestination);

				this.groupRules.add(ofRuleDestination);

				// end set destination rules

			}

		}

	}

	/**
	 * utility method for breaking the IP subnet into more chunks of subnets
	 * based on how many flows are allowed - consider having this in the
	 * utilities package
	 * 
	 * @param ipAndSubnet
	 * @param flowForEachValue
	 * @return
	 */
	private ArrayList<IPAddress> generateIPandSubnets(String[] ipAndSubnet,
			int flowForEachValue) {
		ArrayList<IPAddress> ipAddressValues = new ArrayList<IPAddress>();
		FlowscaleController.logger.debug("in method generate ips and subnets");
		String byteValue = Long.toBinaryString(flowForEachValue);
		FlowscaleController.logger.debug("IP and subnet details {}",
				ipAndSubnet);
		FlowscaleController.logger.debug("group type is {}", type);
		int newSubnetValue = Integer.parseInt(ipAndSubnet[1])
				+ byteValue.length() - 1;

		int numberOfValues = (int) Math.pow(2, (byteValue.length() - 1));
		if (newSubnetValue > 32) {
			newSubnetValue = 32;

			numberOfValues = 32 - Integer.parseInt(ipAndSubnet[1]);
		}

		int oldByteValue = 32 - Integer.parseInt(ipAndSubnet[1]);

		int originalNumberOfFlows = (int) Math.pow(2, oldByteValue);
		if (originalNumberOfFlows < numberOfValues) {
			numberOfValues = originalNumberOfFlows;

		}

		IPv4Address ipv4Address = new IPv4Address(ipAndSubnet[0]);
		ipv4Address.setSubnet(Integer.parseInt(ipAndSubnet[1]));
		int ipAddressInt = ipv4Address.getIPv4AddressInt();

		IPAddress ipAddress = new IPAddress();
		ipAddress.setIpAddressValue(ipAddressInt);
		ipAddress.setSubnet(newSubnetValue);
		ipAddressValues.add(ipAddress);
		for (int i = 0; i < numberOfValues - 1; i++) {

			ipAddressInt = IPAddressUtility.incrementSubnet(ipAddressInt,
					newSubnetValue);

			ipAddress = new IPAddress();
			ipAddress.setIpAddressValue(ipAddressInt);
			ipAddress.setSubnet(newSubnetValue);
			ipAddressValues.add(ipAddress);

		}

		return ipAddressValues;

	}

	/**
	 * utility method will instantiate an OFRule with a TransportPort rule
	 * 
	 * @param protocol
	 * @param direction
	 */
	private void generateTransportPortRules(byte protocol, byte direction) {

		// loop round robin through output ports

		int i = 0;

		int actionPort = 0;

		for (String s : values) {

			int portMatch = Integer.parseInt(s);
			if (this.dropPortGroup) {
				actionPort = -1;
			} else {

				if (outputPortsUp.size() == 0) {

					FlowscaleController.logger
							.info("all group ports are down, no flows are added ");
					return;
				}

				try {
					java.util.Random generator = new java.util.Random();
					int randomIndex = generator.nextInt(outputPortsUp.size());
					actionPort = this.outputPortsUp.get(randomIndex);

					actionPort = this.outputPortsUp.get(portCounter
							% outputPortsUp.size());
					portCounter = portCounter + 1;

				} catch (ArithmeticException ae) {
					throw new ArithmeticException();
				} catch (IllegalArgumentException iae) {
					FlowscaleController.logger
							.info("No ports are up for this group exiting...");
					continue;
				}

			}
			i++;

			OFRule rule = new OFRule();

			rule.setPort(actionPort);
			OFMatch match = new OFMatch();
			match.setDataLayerType(ETHERTYPE_IP);
			match.setNetworkProtocol(protocol);
			rule.setPriority(this.priority);

			if (direction == 0) {
				match.setTransportSource((short) portMatch);
				match.setWildcards(OFMatch.OFPFW_ALL ^ OFMatch.OFPFW_DL_TYPE
						^ OFMatch.OFPFW_NW_PROTO ^ OFMatch.OFPFW_TP_SRC);
			} else if (direction == 1) {
				match.setTransportDestination((short) portMatch);
				match.setWildcards(OFMatch.OFPFW_ALL ^ OFMatch.OFPFW_DL_TYPE
						^ OFMatch.OFPFW_NW_PROTO ^ OFMatch.OFPFW_TP_DST);
			}

			rule.setMatch(match);

			groupRules.add(rule);

		}

	}

	/**
	 * private utility method in order to instantiate an OFRule that contains an
	 * OFMatch for ethertypes
	 */
	private void generateEtherRules() {

		int i = 0;

		int actionPort = 0;

		for (String s : values) {

			int etherTypeMatch = (int) HexString.toLong(s);
			FlowscaleController.logger.debug("etherTypematch is {}",
					etherTypeMatch);

			if (this.dropPortGroup) {
				actionPort = -1;

			} else {
				if (outputPortsUp.size() == 0) {
					FlowscaleController.logger
							.info("No ports up , can't add flow to switch");
					return;
				}
				actionPort = outputPortsUp.get(i % outputPortsUp.size());
			}
			i++;

			OFRule rule = new OFRule();
			rule.setPriority(this.priority);
			rule.setPort(actionPort);
			OFMatch match = new OFMatch();
			match.setDataLayerType((short) etherTypeMatch);
			rule.setWildcards(OFMatch.OFPFW_ALL ^ OFMatch.OFPFW_DL_TYPE);
			match.setWildcards((short) rule.getWildcards());
			rule.setMatch(match);

			groupRules.add(rule);

		}

	}

	/**
	 * this method reads the list of OFRules and pushes them to the switch, if
	 * the rules do no exist, then the generateRules() is called and the list of
	 * OFRules is generated
	 */
	public void pushRules() {
		if (this.groupRules == null || this.groupRules.size() == 0)
			try {
				generateRules();
			} catch (ArithmeticException ae) {
				FlowscaleController.logger
						.error("no output ports are up , no rules injected");
				FlowscaleController.logger.error("{}", ae);
				return;
			} catch (ArrayIndexOutOfBoundsException aeiob) {
				FlowscaleController.logger
						.error("There seems to be a conflict in the values and group type!");
				FlowscaleController.logger.error("{}", aeiob);
				return;
			}

		// handle two switches later
		FlowscaleController.logger.trace("in ruleset pushing rules now: ");

		FlowscaleController.logger.trace("group rules are {} ", groupRules);
		// handle output switch first

		IOFSwitch outputSwitch = flowscaleController.getIBeaconProvider()
				.getSwitches().get(outputSwitchDatapathId);

		OFFlowMod flowModRule = new OFFlowMod();
		flowModRule.setType(OFType.FLOW_MOD);
		flowModRule.setCommand(OFFlowMod.OFPFC_ADD);
		flowModRule.setHardTimeout((short) 0);
		flowModRule.setIdleTimeout((short) 0);
		flowModRule.setBufferId(-1);

		int count = 0;

		int actionOutputLength;

		if (this.dropPortGroup) {
			actionOutputLength = 0;
		} else {
			actionOutputLength = OFActionOutput.MINIMUM_LENGTH;
		}

		for (OFRule rule : this.groupRules) {
			count++;

			flowModRule.setMatch(rule.getMatch());
			if (!this.dropPortGroup) {
				// add mirroring capabitlites

				HashMap<Short, Short> switchMirrors = flowscaleController
						.getSwitchFlowMirrorPortsHashMap().get(
								outputSwitch.getId());

				if (switchMirrors != null) {
					FlowscaleController.logger.debug("Mirror hashmap {}",
							switchMirrors.toString());
					OFActionOutput actionPort = null;
					try {
						FlowscaleController.logger.debug("Mirror actions = {}",
								rule.getActions());
						if (rule.getActions() != null) {

							actionPort = ((OFActionOutput) rule.getActions()
									.get(0));

							FlowscaleController.logger.debug(
									"Action port for Mirror is {}",
									actionPort.getPort());
						}
						Short mirrorPortValue = switchMirrors.get(actionPort
								.getPort());
						if (mirrorPortValue != null) {
							FlowscaleController.logger
									.debug("mirror {} for switch {} set ",
											actionPort.getPort()
													+ ","
													+ mirrorPortValue
															.toString(),
											HexString.toHexString(outputSwitch
													.getId()));
							rule.setMirrorPort(mirrorPortValue);
						}

					} catch (NumberFormatException nfe) {
						FlowscaleController.logger.error(
								"OFAction {} is not directed to a switch port",
								flowModRule.getActions().get(0).toString());
					}

				}

				// after checking mirrors set actions
				flowModRule.setActions(rule.getActions());
			}
			flowModRule.setLength(U16.t(OFFlowMod.MINIMUM_LENGTH
					+ actionOutputLength));
			flowModRule.setPriority(rule.getPriority());

			try {
				FlowscaleController.logger.debug("{}", flowModRule);
				outputSwitch.getOutputStream().write(flowModRule);
				if (count >= flowscaleController.getMaximumFlowsToPush()) {
					count = 0;

					outputSwitch.getOutputStream().flush();

					Thread.sleep(5000);

				}
			} catch (InterruptedException interruptedException) {
				FlowscaleController.logger.error("{}", interruptedException);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				FlowscaleController.logger.error("{}", e);
			}

		}

		try {

			outputSwitch.getOutputStream().flush();

		} catch (Exception e) {
			FlowscaleController.logger.error("{}", e);
		}

	}

	/**
	 * interface to edit a group, currently it may not be safe to call this
	 * method since it is not thoroughly tested , especially the part where
	 * flows are modified to the switch
	 * 
	 * @param updateType
	 * @param updateValue
	 */
	public void editGroup(String updateType, String updateValue) {

		String[] updateValues = updateValue.split(",");
		IOFSwitch sw = flowscaleController.getIBeaconProvider().getSwitches()
				.get(this.inputSwitchDatapathId);
		int i = 0;

		if (updateType.equals("values")) {

			for (String s : updateValues) {

				String[] command = s.split(" ");

				if (command[0].equals("remove")) {

					// removeRules(command[1], command[2]);

				} else if (command[0].equals("add")) {

					// addRules(command[1], command[2]);
				}
			}

		} else if (updateType.equals("ports")) {

			for (String s : updateValues) {

				String[] command = s.split(" ");

				if (command[0].equals("remove")) {

					alert(sw, Short.parseShort(command[1]), null,
							OFPortReason.OFPPR_DELETE);

				} else if (command[0].equals("add")) {
					alert(sw, Short.parseShort(command[1]), null,
							OFPortReason.OFPPR_ADD);

				}
			}

		}

	}

	/**
	 * remove thsi group, an interface where the group can be removed from a web
	 * user interface or a cli, and delete the rules from the switch
	 */
	public void removeGroup() {

		deleteAllRules();

	}

	private void deleteAllRules() {
		// algorithm to nuke all rules from all switches
		IOFSwitch sw = flowscaleController.ibeaconProvider.getSwitches().get(
				this.outputSwitchDatapathId);
		OFFlowMod flowToDelete = new OFFlowMod();
		flowToDelete.setCommand(OFFlowMod.OFPFC_DELETE_STRICT);
		flowToDelete.setOutPort(OFPort.OFPP_NONE);
		for (OFRule ofRule : this.groupRules) {

			flowToDelete.setMatch(ofRule.getMatch());
			flowToDelete.setPriority(ofRule.getPriority());
			flowToDelete.setLength(U16.t(OFFlowMod.MINIMUM_LENGTH));

			try {

				FlowscaleController.logger.debug(
						" Attempting to delete flow  {}", flowToDelete);
				sw.getOutputStream().write(flowToDelete);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				FlowscaleController.logger.error("{}", e);
			}

		}

		try {
			sw.getOutputStream().flush();
		} catch (IOException ioe) {
			FlowscaleController.logger.error("{}", ioe);
		}

	}

	/**
	 * This method is invoked by the controller when an update comes in from the
	 * the switch to the controller , the method is charged with updating the
	 * rules in the switch if there is a rule pointing to the updated port, thus
	 * conducting load balancing
	 * 
	 * @param sw
	 *            IOFSwitch that caused this alert and its port update
	 * @param portNum
	 *            the port number that has been update
	 * @param physicalPort
	 * @param reason
	 *            the reason for the update
	 */
	public void alert(IOFSwitch sw, short portNum, OFPhysicalPort physicalPort,
			OFPortReason reason) {
		OFFlowMod updateFlow = new OFFlowMod();
		OFFlowMod updateFlow2 = new OFFlowMod();
		updateFlow.setType(OFType.FLOW_MOD);
		updateFlow.setCommand(OFFlowMod.OFPFC_ADD);
		updateFlow.setHardTimeout((short) 0);
		updateFlow.setIdleTimeout((short) 0);
		int portStatus = 0;

		FlowscaleController.logger
				.trace("current rules for group with update port");

		for (OFRule rule : this.groupRules) {

			FlowscaleController.logger.trace("rule match is {} and port is {}",
					rule.getMatch().toString(), rule.getActions().get(0));

		}

		if (physicalPort == null) {

			if (reason == OFPortReason.OFPPR_ADD) {

				portStatus = 0;

			} else if (reason == OFPortReason.OFPPR_DELETE) {
				portStatus = 1;
			}

		} else if (reason == null) {

			// just check that the last bit is zero then set the status to 0 or
			// 1 meaning port is down
			portStatus = physicalPort.getState();
			if (portStatus % 2 == 0) {
				portStatus = 0;
			} else {
				portStatus = 1;
			}

		}

		FlowscaleController.logger.info(
				"updating flows since there is a port modification at port {}",
				physicalPort.getPortNumber());

		switch (portStatus) {

		case 0:
			FlowscaleController.logger
					.info("a port belonging to the group output ports is up");

			if (!(outputPortsUp
					.contains(new Short(physicalPort.getPortNumber())))) {
				outputPortsUp.add(physicalPort.getPortNumber());
			}
			int ruleDistribution = this.outputPortsUp.size();

			int i = 1;

			FlowscaleController.logger.info(
					"Modifying flows for switch {} to add port {}",
					HexString.toHexString(sw.getId()),
					physicalPort.getPortNumber());
			ArrayList<OFRule> checkedRules = new ArrayList<OFRule>();
			for (OFRule ofRule : this.groupRules) {

				if (checkedRules.contains(ofRule)) {
					continue;
				}

				if (i % ruleDistribution == 0) {

					OFActionOutput ofActionOutput = new OFActionOutput();
					ofActionOutput.setPort(portNum);

					String thisMatchString = ofRule.getMatch().toString();
					String otherMatchString = "";
					OFRule otherDirectionRule = null;
					if (thisMatchString.indexOf("nw_src") != -1) {
						otherMatchString = thisMatchString.replace("nw_src",
								"nw_dst");

					} else if (thisMatchString.indexOf("nw_dst") != -1) {
						otherMatchString = thisMatchString.replace("nw_dst",
								"nw_src");
					}

					if (this.type == IP_TYPE) {

						for (OFRule ofRule2 : this.groupRules) {

							if (ofRule2.getMatch().toString()
									.equals(otherMatchString)) {
								otherDirectionRule = ofRule2;
								checkedRules.add(ofRule2);
								break;
							}

						}

					}

					ArrayList<OFAction> actionList = ofRule.getActions();
					actionList.clear();
					HashMap<Short, Short> switchMirrors = flowscaleController
							.getSwitchFlowMirrorPortsHashMap().get(sw.getId());
					actionList.add(ofActionOutput);
					if (switchMirrors != null) {
						Short mirrorPort = switchMirrors.get(portNum);
						if (mirrorPort != null) {
							OFActionOutput mirrorAction = new OFActionOutput();
							mirrorAction.setPort(mirrorPort);
							actionList.add(mirrorAction);

						}

					}

					updateFlow.setMatch(ofRule.getMatch());
					updateFlow.setBufferId(-1);
					updateFlow.setPriority(ofRule.getPriority());

					updateFlow.setActions(actionList);
					updateFlow.setLength(U16.t(OFFlowMod.MINIMUM_LENGTH
							+ OFActionOutput.MINIMUM_LENGTH));

					if (this.type == IP_TYPE) {

						updateFlow2 = new OFFlowMod();

						updateFlow2.setType(OFType.FLOW_MOD);
						updateFlow2.setCommand(OFFlowMod.OFPFC_ADD);
						updateFlow2.setHardTimeout((short) 0);
						updateFlow2.setIdleTimeout((short) 0);

						ArrayList<OFAction> actionList2 = otherDirectionRule
								.getActions();
						actionList2.clear();
						HashMap<Short, Short> switchMirrors2 = flowscaleController
								.getSwitchFlowMirrorPortsHashMap().get(
										sw.getId());
						actionList2.add(ofActionOutput);
						if (switchMirrors2 != null) {
							Short mirrorPort = switchMirrors2.get(portNum);
							if (mirrorPort != null) {
								OFActionOutput mirrorAction = new OFActionOutput();
								mirrorAction.setPort(mirrorPort);
								actionList2.add(mirrorAction);

							}

						}

						updateFlow2.setMatch(otherDirectionRule.getMatch());
						updateFlow2.setBufferId(-1);
						updateFlow2.setPriority(otherDirectionRule
								.getPriority());

						updateFlow2.setActions(actionList2);
						updateFlow2.setLength(U16.t(OFFlowMod.MINIMUM_LENGTH
								+ OFActionOutput.MINIMUM_LENGTH));
					}

					try {
						FlowscaleController.logger.trace("modifying flow   {}",
								updateFlow);

						sw.getOutputStream().write(updateFlow);
						if (this.type == IP_TYPE) {
							sw.getOutputStream().write(updateFlow2);
						}

					} catch (IOException e) {
						// TODO Auto-generated catch block
						FlowscaleController.logger.error("{}", e);
					}

				}

				i++;
			}
			FlowscaleController.logger.info("Modification of flows completed");

			break;
		case 1:

			i = 0;
			FlowscaleController.logger.trace("outputPortUp before removal {}",
					outputPortsUp);

			outputPortsUp.remove(new Short(physicalPort.getPortNumber()));
			FlowscaleController.logger.trace("outputPortsUp after removal {}",
					outputPortsUp);

			FlowscaleController.logger
					.info("port {} for switch {} is down so flows for this will be updated",
							physicalPort.getPortNumber(),
							HexString.toHexString(sw.getId()));
			int count = 0;

			ArrayList<OFRule> checkedRulesonPortDown = new ArrayList<OFRule>();

			for (OFRule ofRule : this.groupRules) {

				if (checkedRulesonPortDown.contains(ofRule)) {
					continue;
				}

				OFActionOutput ofActionOutput = (OFActionOutput) ofRule
						.getActions().get(0);

				FlowscaleController.logger.trace(
						"match here is {} and port is {}", ofRule.getMatch(),
						ofActionOutput.getPort());

				if (ofActionOutput.getPort() == portNum) {
					short newPort = 0;
					try {
						newPort = this.outputPortsUp.get(i++
								% outputPortsUp.size());
						ofActionOutput.setPort(newPort);

					} catch (ArithmeticException aeException) {
						FlowscaleController.logger
								.info("No group ports are up , ...no flows redirected");
						continue;
					}
					ArrayList<OFAction> actionList = ofRule.getActions();
					actionList.clear();
					// add mirror ports if there are any

					HashMap<Short, Short> switchMirrors = flowscaleController
							.getSwitchFlowMirrorPortsHashMap().get(sw.getId());
					actionList.add(ofActionOutput);
					if (switchMirrors != null) {
						Short mirrorPort = switchMirrors.get(newPort);
						if (mirrorPort != null) {
							OFActionOutput mirrorAction = new OFActionOutput();
							mirrorAction.setPort(mirrorPort);
							actionList.add(mirrorAction);

						}

					}
					String thisMatchString = ofRule.getMatch().toString();
					String otherMatchString = "";
					OFRule otherDirectionRule = null;
					if (thisMatchString.indexOf("nw_src") != -1) {
						otherMatchString = thisMatchString.replace("nw_src",
								"nw_dst");

					} else if (thisMatchString.indexOf("nw_dst") != -1) {
						otherMatchString = thisMatchString.replace("nw_dst",
								"nw_src");
					}
					if (this.type == IP_TYPE) {

						for (OFRule ofRule2 : this.groupRules) {

							if (ofRule2.getMatch().toString()
									.equals(otherMatchString)) {
								otherDirectionRule = ofRule2;
								checkedRulesonPortDown.add(ofRule2);
								break;

							}

						}

					}

					updateFlow.setMatch(ofRule.getMatch());
					updateFlow.setBufferId(-1);
				
					updateFlow.setPriority(ofRule.getPriority());

					updateFlow.setActions(actionList);

					if (this.type == IP_TYPE) {

						updateFlow2 = new OFFlowMod();
						updateFlow2.setType(OFType.FLOW_MOD);
						updateFlow2.setCommand(OFFlowMod.OFPFC_ADD);
						updateFlow2.setHardTimeout((short) 0);
						updateFlow2.setIdleTimeout((short) 0);

						ArrayList<OFAction> actionList2 = otherDirectionRule
								.getActions();
						actionList2.clear();
						HashMap<Short, Short> switchMirrors2 = flowscaleController
								.getSwitchFlowMirrorPortsHashMap().get(
										sw.getId());
						actionList2.add(ofActionOutput);
						if (switchMirrors2 != null) {
							Short mirrorPort = switchMirrors2.get(portNum);
							if (mirrorPort != null) {
								OFActionOutput mirrorAction = new OFActionOutput();
								mirrorAction.setPort(mirrorPort);
								actionList2.add(mirrorAction);

							}

						}

						updateFlow2.setMatch(otherDirectionRule.getMatch());
						updateFlow2.setBufferId(-1);
						updateFlow2.setPriority(otherDirectionRule.getPriority());

						updateFlow2.setActions(actionList2);

					}

					try {
						FlowscaleController.logger.trace("updating flow {}",
								updateFlow);
						sw.getOutputStream().write(updateFlow);
						if (type == IP_TYPE) {
							sw.getOutputStream().write(updateFlow2);
						}
						if (count >= flowscaleController
								.getMaximumFlowsToPush()) {
							count = 0;
							sw.getOutputStream().flush();
							FlowscaleController.logger
									.trace("reached maximum flows to push to switch thread sleeping");
							try {
								Thread.sleep(5000);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								FlowscaleController.logger.error("{}", e);
							}

						}

					} catch (IOException e) {
						// TODO Auto-generated catch block
						FlowscaleController.logger.error("{}", e);
					}

				}

			}
			FlowscaleController.logger
					.info("Flow modification for port removal complete");

			break;

		}
		try {
			FlowscaleController.logger.trace("flushing rules");
			sw.getOutputStream().flush();
			FlowscaleController.logger.info("update complete");
		} catch (IOException ioe) {
			FlowscaleController.logger.error("{}", ioe);
		}

	}

	// list of setters and getters

	public int getMaximumFlowsAllowed() {
		return maximumFlowsAllowed;
	}

	public void setMaximumFlowsAllowed(int maximumFlowsAllowed) {
		this.maximumFlowsAllowed = maximumFlowsAllowed;
	}

	public int getGrouopId() {
		return groupId;
	}

	public void setGrouopId(int grouopId) {
		this.groupId = grouopId;
	}

	public String getGroupName() {
		return groupName;
	}

	public void setGroupName(String groupName) {
		this.groupName = groupName;
	}

	public List<Short> getInputPorts() {
		return inputPorts;
	}

	public void setInputPorts(List<Short> inputPorts) {
		this.inputPorts = inputPorts;
	}

	public List<Short> getOutputPorts() {
		return outputPorts;
	}

	public void setOutputPorts(List<Short> outputPorts) {
		this.outputPorts = outputPorts;
	}

	public long getInputSwitchDatapathId() {
		return inputSwitchDatapathId;
	}

	public void setInputSwitchDatapathId(long inputSwitchDatapathId) {
		this.inputSwitchDatapathId = inputSwitchDatapathId;
	}

	public long getOutputSwitchDatapathId() {
		return outputSwitchDatapathId;
	}

	public void setOutputSwitchDatapathId(long outputSwitchDatapathId) {
		this.outputSwitchDatapathId = outputSwitchDatapathId;
	}

	public int getType() {
		return type;
	}

	public void setType(int type) {
		this.type = type;
	}

	public String[] getValues() {
		return values;
	}

	public void setValues(String[] values) {
		this.values = values;
	}

}
