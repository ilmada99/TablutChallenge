package it.unibo.ai.didattica.competition.tablut.client;

import java.io.IOException;
import java.net.UnknownHostException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import SearchStrategy.IterativeDeepeningAlphaBetaSearchTablut;
import aima.core.search.adversarial.Game;
import it.unibo.ai.didattica.competition.tablut.domain.Action;
import it.unibo.ai.didattica.competition.tablut.domain.GameDaloTablut;
import it.unibo.ai.didattica.competition.tablut.domain.State;
import it.unibo.ai.didattica.competition.tablut.domain.StateTablut;
import it.unibo.ai.didattica.competition.tablut.domain.State.Turn;

public class TablutDalo extends TablutClient {
	public static final String NAME = "Cliente";
	private IterativeDeepeningAlphaBetaSearchTablut<State, Action, String> searchStrategy;
	Game<State, Action, String> rules;

	//timeout= Maximal computation time in seconds.
	public TablutDalo(String player, String name, int timeout, String ipAddress)
			throws UnknownHostException, IOException {
		super(player, name, timeout, ipAddress);
		
		rules = new GameDaloTablut(new StateTablut(), 2, 2, "log", "White", "Black",this.getPlayer());
		searchStrategy = new IterativeDeepeningAlphaBetaSearchTablut<>(rules, 0.0, GameDaloTablut.getMaxValueHeuristic(), timeout - 1);
		searchStrategy.printStatistics=true;
		searchStrategy.graphOptimization=false;
	}

	public static void main(String[] args) {
		String role;
		int timeout;
		String ipAddress;
		if (args.length != 3) {
			System.out.println("Errore args: <role> <timeout> <ipAddress>");
			System.out.println("Default Setting:\nrole: white\ntimeout=60 sec\nipAddress=\"Localhost\"");
			role = "white";
			timeout = 5;
			ipAddress = "localhost";
		} else {
			role = args[0];
			if (!role.equalsIgnoreCase("white") && !role.equalsIgnoreCase("black")) {
				System.out.println("Player role must be BLACK or WHITE");
				System.exit(1);
			}
			timeout = 5;
			try {
				timeout = Integer.parseInt(args[1]);
			} catch (NumberFormatException e) {
				System.out.println("The timeout has to be an integer");
				System.exit(1);
			}
			ipAddress = args[2];
		}

		System.out.println("Connecting to the server...");
		TablutDalo client = null;
		try {
			client = new TablutDalo(role, NAME, timeout, ipAddress);
		} catch (InvalidParameterException e) {
			System.out.println(e.getMessage());
			System.exit(1);
		} catch (UnknownHostException e) {
			System.out.println("Unknown host: " + ipAddress);
			System.exit(1);
		} catch (IOException e) {
			System.out.println("Error connecting to the server: " + e.getMessage());
			System.exit(1);
		}
		System.out.println("Connected");
		client.run();
	}

	@Override
	public void run() {
		try {
			System.out.println("Declaring name...");
			this.declareName();
		} catch (Exception e) {
			System.out.println("Error declaring name: " + e.getMessage());
			System.exit(1);
		}

		State state;

		System.out.println("You are player " + this.getPlayer());
		System.out.println("Start of the match!");

		while (true) {
			try {
				this.read(); // reading JSON from server
			} catch (ClassNotFoundException | IOException e1) {
				System.out.println("Error reading the state: " + e1.getMessage());
				System.exit(1);
			}

			state = this.getCurrentState();
			state.setDrawConditions(((GameDaloTablut)rules).getDrawConditions());
			// check if the game is finish

			// I won
			if (this.getPlayer().equals(State.Turn.WHITE) && state.getTurn().equals(State.Turn.WHITEWIN)
					|| this.getPlayer().equals(State.Turn.BLACK) && state.getTurn().equals(State.Turn.BLACKWIN)) {
				System.out.println("YOU WIN!");
				System.exit(0);
			}
			// I lose
			else if (this.getPlayer().equals(State.Turn.WHITE) && state.getTurn().equals(State.Turn.BLACKWIN)
					|| this.getPlayer().equals(State.Turn.BLACK) && state.getTurn().equals(State.Turn.WHITEWIN)) {
				System.out.println("YOU LOSE!");
				System.exit(0);
			}
			// Draw
			else if (state.getTurn().equals(StateTablut.Turn.DRAW)) {
				System.out.println("DRAW!");
				System.exit(0);
			}

			// My turn
			else if (this.getPlayer().equals(this.getCurrentState().getTurn())) {
				Action chosenMove = null;
				System.out.println("I am thinking...");
				try {
					chosenMove = this.searchStrategy.makeDecision(state);
					System.out.println("Chosen move: " + chosenMove);
					((GameDaloTablut)rules).checkMove(state, chosenMove);
				} catch (Exception e) {
					System.err.println("Something went wrong, or an illegal action has been chosen: " + e.getMessage());
					chosenMove = emergencyMove(state);
					System.out.println("New chosen emergecy move:" + chosenMove);
				}
				try {
					this.write(chosenMove);
				} catch (IOException | ClassNotFoundException e) {
					System.out.println("Error sending the move: " + e.getMessage());
					System.exit(1);
				}
			}
			// Adversary turn
			else {
				System.out.println("Waiting for your opponent move...");
			}
		}

	}

	/*
	 * Generate a random valid move
	 * This method loops unitl a valid move is found (which may actually never happen), so use this in case of emergency
	 */
	private Action emergencyMove(State state) {
		// Mio turno
		int[] buf;
		List<int[]> pawns = new ArrayList<>();
		List<int[]> empty = new ArrayList<>();
		if (this.getCurrentState().getTurn().equals(StateTablut.Turn.WHITE)) {
			for (int i = 0; i < state.getBoard().length; i++) {
				for (int j = 0; j < state.getBoard().length; j++) {
					if (state.getPawn(i, j).equalsPawn(State.Pawn.WHITE.toString())
							|| state.getPawn(i, j).equalsPawn(State.Pawn.KING.toString())) {
						buf = new int[2];
						buf[0] = i;
						buf[1] = j;
						pawns.add(buf);
					} else if (state.getPawn(i, j).equalsPawn(State.Pawn.EMPTY.toString())) {
						buf = new int[2];
						buf[0] = i;
						buf[1] = j;
						empty.add(buf);
					}
				}
			}
		}else {
			for (int i = 0; i < state.getBoard().length; i++) {
				for (int j = 0; j < state.getBoard().length; j++) {
					if (state.getPawn(i, j).equalsPawn(State.Pawn.BLACK.toString())) {
						buf = new int[2];
						buf[0] = i;
						buf[1] = j;
						pawns.add(buf);
					} else if (state.getPawn(i, j).equalsPawn(State.Pawn.EMPTY.toString())) {
						buf = new int[2];
						buf[0] = i;
						buf[1] = j;
						empty.add(buf);
					}
				}
			}
		}

		int[] selected = null;

		boolean found = false;
		Action a = null;
		try {
			a = new Action("z0", "z0", this.getCurrentState().getTurn());
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		while (!found) {
			if (pawns.size() > 1) {
				selected = pawns.get(new Random().nextInt(pawns.size() - 1));
			} else {
				selected = pawns.get(0);
			}

			String from = this.getCurrentState().getBox(selected[0], selected[1]);

			selected = empty.get(new Random().nextInt(empty.size() - 1));
			String to = this.getCurrentState().getBox(selected[0], selected[1]);

			try {
				a = new Action(from, to, this.getCurrentState().getTurn());
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}

			try {
				((GameDaloTablut)rules).checkMove(state, a);
				found = true;
			} catch (Exception e) {}
		}
		
		return a;
	}
}
