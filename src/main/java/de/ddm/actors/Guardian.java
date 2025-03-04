package de.ddm.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import de.ddm.actors.patterns.Reaper;
import de.ddm.configuration.SystemConfiguration;
import de.ddm.serialization.AkkaSerializable;
import de.ddm.singletons.SystemConfigurationSingleton;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Guardian extends AbstractBehavior<Guardian.Message> {

	////////////////////
	// Actor Messages //
	////////////////////

	public interface Message extends AkkaSerializable {
	}

	@NoArgsConstructor
	public static class StartMessage implements Message {
		private static final long serialVersionUID = -6896669928271349802L;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ShutdownMessage implements Message {
		private static final long serialVersionUID = 7516129288777469221L;
		private ActorRef<Message> initiator;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ReceptionistListingMessage implements Message {
		private static final long serialVersionUID = 2336368568740749020L;
		Receptionist.Listing listing;
	}

	////////////////////////
	// Actor Construction //
	////////////////////////

	public static final String DEFAULT_NAME = "userGuardian";
	//this var allows to change the amount of workers used
	public static final int WORKER_SIZE = 3;
	public static final ServiceKey<Guardian.Message> guardianService = ServiceKey.create(Guardian.Message.class, DEFAULT_NAME + "Service");

	public static Behavior<Message> create() {
		return Behaviors.setup(
				context -> Behaviors.withTimers(timers -> new Guardian(context, timers)));
	}

	private Guardian(ActorContext<Message> context, TimerScheduler<Message> timers) {
		super(context);

		this.timers = timers;

		this.reaper = context.spawn(Reaper.create(), Reaper.DEFAULT_NAME);
		this.master = this.isMaster() ? context.spawn(Master.create(), Master.DEFAULT_NAME) : null;
		//create as many workers as intended
		for(int i = 0; i < WORKER_SIZE; i++){
			this.workers.add(context.spawn(Worker.create(), Worker.DEFAULT_NAME+i));
		}

		context.getSystem().receptionist().tell(Receptionist.register(guardianService, context.getSelf()));

		final ActorRef<Receptionist.Listing> listingResponseAdapter = context.messageAdapter(Receptionist.Listing.class, ReceptionistListingMessage::new);
		context.getSystem().receptionist().tell(Receptionist.subscribe(guardianService, listingResponseAdapter));
	}

	private boolean isMaster() {
		return SystemConfigurationSingleton.get().getRole().equals(SystemConfiguration.MASTER_ROLE);
	}

	/////////////////
	// Actor State //
	/////////////////

	private final TimerScheduler<Message> timers;

	private Set<ActorRef<Message>> userGuardians = new HashSet<>();

	private final ActorRef<Reaper.Message> reaper;
	private ActorRef<Master.Message> master;
	private List<ActorRef<Worker.Message>> workers = new ArrayList<>();

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive<Message> createReceive() {
		return newReceiveBuilder()
				.onMessage(StartMessage.class, this::handle)
				.onMessage(ShutdownMessage.class, this::handle)
				.onMessage(ReceptionistListingMessage.class, this::handle)
				.build();
	}

	private Behavior<Message> handle(StartMessage message) {
		if (this.master != null)
			this.master.tell(new Master.StartMessage());
		return this;
	}

	private Behavior<Message> handle(ShutdownMessage message) {
		ActorRef<Message> self = this.getContext().getSelf();

		if ((message.getInitiator() != null) || this.isClusterDown()) {
			this.shutdown();
		} else {
			for (ActorRef<Message> userGuardian : this.userGuardians)
				if (!userGuardian.equals(self))
					userGuardian.tell(new ShutdownMessage(self));

			if (!this.timers.isTimerActive("ShutdownReattempt"))
				this.timers.startTimerAtFixedRate("ShutdownReattempt", message, Duration.ofSeconds(5), Duration.ofSeconds(5));
		}
		return this;
	}

	private boolean isClusterDown() {
		return this.userGuardians.isEmpty() || (this.userGuardians.contains(this.getContext().getSelf()) && this.userGuardians.size() == 1);
	}

	private void shutdown() {
		if (!this.workers.isEmpty()) {
			for (ActorRef<Worker.Message> worker : workers) {
				worker.tell(new Worker.ShutdownMessage());
			}
			workers.clear();
		}
		if (this.master != null) {
			this.master.tell(new Master.ShutdownMessage());
			this.master = null;
		}
	}

	private Behavior<Message> handle(ReceptionistListingMessage message) {
		this.userGuardians = message.getListing().getServiceInstances(Guardian.guardianService);

		if (this.timers.isTimerActive("ShutdownReattempt") && this.isClusterDown())
			this.shutdown();

		return this;
	}
}