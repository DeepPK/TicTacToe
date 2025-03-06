import com.example.tictactoe.*;
import com.example.tictactoe.TicTacToeGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TicTacToeServer {
    private final int port;
    private final Server server;
    private final RoomManager roomManager = new RoomManager();

    public TicTacToeServer(int port) {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new TicTacToeService(roomManager))
                .build();
    }

    public void start() throws IOException {
        server.start();
        System.out.println("Server started on port " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutting down server...");
            try {
                TicTacToeServer.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }));
    }

    private void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) throws Exception {
        TicTacToeServer server = new TicTacToeServer(50051);
        server.start();
        server.blockUntilShutdown();
    }

    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    static class TicTacToeService extends TicTacToeGrpc.TicTacToeImplBase {
        private final RoomManager roomManager;

        TicTacToeService(RoomManager roomManager) {
            this.roomManager = roomManager;
        }

        @Override
        public void createRoom(RoomRequest request, StreamObserver<RoomResponse> responseObserver) {
            RoomResponse response = roomManager.createRoom(
                    request.getRoomName(),
                    request.getPlayerName()
            );
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void listRooms(Empty request, StreamObserver<RoomList> responseObserver) {
            responseObserver.onNext(roomManager.getRoomList());
            responseObserver.onCompleted();
        }

        @Override
        public void joinRoom(RoomRequest request, StreamObserver<GameState> responseObserver) {
            roomManager.joinRoom(
                    request.getRoomName(),
                    request.getPlayerName(),
                    responseObserver
            );
        }

        @Override
        public void makeMove(Move request, StreamObserver<MoveResult> responseObserver) {
            boolean success = roomManager.handleMove(
                    request.getGameId(),
                    request.getPlayerName(),
                    request.getPosition()
            );
            responseObserver.onNext(MoveResult.newBuilder()
                    .setSuccess(success)
                    .setMessage(success ? "Move accepted" : "Invalid move")
                    .build());
            responseObserver.onCompleted();
        }
    }

    static class RoomManager {
        private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
        private final AtomicInteger roomCounter = new AtomicInteger();

        public RoomResponse createRoom(String roomName, String playerName) {
            String roomId = "room-" + roomCounter.incrementAndGet();
            rooms.put(roomId, new Room(roomId, roomName));
            return RoomResponse.newBuilder()
                    .setSuccess(true)
                    .setRoomId(roomId)
                    .build();
        }

        public RoomList getRoomList() {
            RoomList.Builder builder = RoomList.newBuilder();
            rooms.forEach((id, room) -> builder.addRooms(RoomInfo.newBuilder()
                    .setRoomId(id)
                    .setRoomName(room.getRoomName())
                    .setPlayersCount(room.getPlayersCount())
                    .setStatus(room.getStatus())
                    .build()));
            return builder.build();
        }

        public void joinRoom(String roomId, String playerName, StreamObserver<GameState> observer) {
            Room room = rooms.get(roomId);
            if (room == null) {
                observer.onError(new StatusRuntimeException(Status.NOT_FOUND));
                return;
            }
            room.addPlayer(playerName, observer);
        }

        public boolean handleMove(String gameId, String playerName, int position) {
            Room room = rooms.get(gameId);
            return room != null && room.makeMove(playerName, position);
        }
    }

    static class Room {
        private final String roomId;
        private final String roomName;
        private final List<Player> players = new CopyOnWriteArrayList<>();
        private Game game;

        public Room(String roomId, String roomName) {
            this.roomId = roomId;
            this.roomName = roomName;
        }

        public synchronized void addPlayer(String name, StreamObserver<GameState> observer) {
            if (players.size() >= 2) {
                observer.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException());
                return;
            }

            String symbol = players.isEmpty() ? "X" : "O";
            players.add(new Player(name, symbol, observer));
            notifyPlayers();

            if (players.size() == 2) {
                startGame();
            }
        }

        private void startGame() {
            this.game = new Game(roomId);
            notifyPlayers();
        }

        public boolean makeMove(String playerName, int position) {
            return game != null && game.makeMove(getPlayerSymbol(playerName), position);
        }

        private String getPlayerSymbol(String playerName) {
            return players.stream()
                    .filter(p -> p.name.equals(playerName))
                    .findFirst()
                    .map(p -> p.symbol)
                    .orElse(null);
        }

        private void notifyPlayers() {
            GameState state = GameState.newBuilder()
                    .setGameId(roomId)
                    .addAllBoard(game != null ? Arrays.asList(game.getBoard()) : List.of())
                    .setCurrentPlayer(game != null ? game.getCurrentPlayer() : "")
                    .setStatus(getStatus())
                    .build();

            players.forEach(p -> p.observer.onNext(state));
        }

        public String getStatus() {
            if (players.size() < 2) return "WAITING";
            return game != null ? game.getStatus() : "WAITING";
        }

        public int getPlayersCount() {
            return players.size();
        }

        public String getRoomName() {
            return roomName;
        }

        static class Player {
            final String name;
            final String symbol;
            final StreamObserver<GameState> observer;

            Player(String name, String symbol, StreamObserver<GameState> observer) {
                this.name = name;
                this.symbol = symbol;
                this.observer = observer;
            }
        }
    }

    static class Game {
        private final String gameId;
        private final String[] board = new String[9];
        private String currentPlayer = "X";
        private String status = "IN_PROGRESS";

        public Game(String gameId) {
            this.gameId = gameId;
        }

        public synchronized boolean makeMove(String symbol, int position) {
            if (!status.equals("IN_PROGRESS")) return false;
            if (position < 0 || position >= 9 || board[position] != null) return false;
            if (!symbol.equals(currentPlayer)) return false;

            board[position] = symbol;
            checkGameStatus();
            currentPlayer = currentPlayer.equals("X") ? "O" : "X";
            return true;
        }

        private void checkGameStatus() {
            String winner = checkWinner();
            status = winner != null ? winner + "_WON" : isBoardFull() ? "DRAW" : "IN_PROGRESS";
        }

        private String checkWinner() {
            int[][] lines = {{0,1,2}, {3,4,5}, {6,7,8}, {0,3,6}, {1,4,7}, {2,5,8}, {0,4,8}, {2,4,6}};
            for (int[] line : lines) {
                String a = board[line[0]], b = board[line[1]], c = board[line[2]];
                if (a != null && a.equals(b) && a.equals(c)) return a;
            }
            return null;
        }

        private boolean isBoardFull() {
            return Arrays.stream(board).allMatch(Objects::nonNull);
        }

        public String[] getBoard() {
            return board;
        }

        public String getCurrentPlayer() {
            return currentPlayer;
        }

        public String getStatus() {
            return status;
        }
    }
}
