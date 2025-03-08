package com.example;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TicTacToeServer {
    private static final Logger logger = LoggerFactory.getLogger(TicTacToeServer.class);
    private final int port;
    private final Server server;
    private final RoomManager roomManager = new RoomManager();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public TicTacToeServer(int port) {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new TicTacToeService(roomManager))
                .build();
    }

    public void start() throws IOException {
        server.start();
        logger.info("Server started on port {}", port);
        scheduler.scheduleAtFixedRate(this::cleanupRooms, 1, 1, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down server...");
            try {
                server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private void cleanupRooms() {
        roomManager.rooms.forEach((id, room) -> {
            if (room.shouldBeRemoved()) {
                roomManager.removeRoom(id);
                logger.info("Cleaned up room: {}", id);
            }
        });
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args) throws Exception {
        TicTacToeServer server = new TicTacToeServer(50051);
        server.start();
        server.blockUntilShutdown();
    }

    static class TicTacToeService extends com.example.tictactoe.TicTacToeGrpc.TicTacToeImplBase {
        private final RoomManager roomManager;

        public TicTacToeService(RoomManager roomManager) {
            this.roomManager = roomManager;
        }

        @Override
        public void createRoom(com.example.tictactoe.CreateRoomRequest request, StreamObserver<com.example.tictactoe.RoomResponse> responseObserver) {
            try {
                com.example.tictactoe.RoomResponse response = roomManager.createRoom(request.getRoomName());
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listRooms(com.example.tictactoe.Empty request, StreamObserver<com.example.tictactoe.RoomList> responseObserver) {
            responseObserver.onNext(roomManager.getRoomList());
            responseObserver.onCompleted();
        }

        @Override
        public void joinRoom(com.example.tictactoe.JoinRoomRequest request, StreamObserver<com.example.tictactoe.GameState> responseObserver) {
            try {
                roomManager.joinRoom(
                        request.getRoomId(),
                        request.getPlayerName(),
                        responseObserver);
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void makeMove(com.example.tictactoe.Move request, StreamObserver<com.example.tictactoe.MoveResult> responseObserver) {
            try {
                boolean success = roomManager.handleMove(
                        request.getGameId(),
                        request.getPlayerName(),
                        request.getPosition());
                responseObserver.onNext(com.example.tictactoe.MoveResult.newBuilder()
                        .setSuccess(success)
                        .setMessage(success ? "Ход принят" : "Некорректный ход")
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void leaveRoom(com.example.tictactoe.LeaveRequest request, StreamObserver<com.example.tictactoe.Empty> responseObserver) {
            roomManager.handlePlayerExit(request.getRoomId(), request.getPlayerName());
            responseObserver.onNext(com.example.tictactoe.Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    static class RoomManager {
        private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
        private final AtomicInteger roomCounter = new AtomicInteger();

        public com.example.tictactoe.RoomResponse createRoom(String roomName) {
            String roomId = "room-" + roomCounter.incrementAndGet();
            rooms.put(roomId, new Room(roomId, roomName));
            return com.example.tictactoe.RoomResponse.newBuilder()
                    .setSuccess(true)
                    .setRoomId(roomId)
                    .build();
        }

        public com.example.tictactoe.RoomList getRoomList() {
            com.example.tictactoe.RoomList.Builder builder = com.example.tictactoe.RoomList.newBuilder();
            rooms.forEach((id, room) -> {
                if (room.getStatus().equals("WAITING") && room.getPlayersCount() == 1) {
                    builder.addRooms(com.example.tictactoe.RoomInfo.newBuilder()
                            .setRoomId(id)
                            .setRoomName(room.getRoomName())
                            .setPlayersCount(room.getPlayersCount())
                            .setStatus(room.getStatus())
                            .build());
                }
            });
            return builder.build();
        }

        public void joinRoom(String roomId, String playerName, StreamObserver<com.example.tictactoe.GameState> observer) {
            Room room = rooms.get(roomId);
            if (room == null) {
                observer.onError(Status.NOT_FOUND.withDescription("Комната не найдена").asRuntimeException());
                return;
            }
            room.addPlayer(playerName, observer);
        }

        public boolean handleMove(String gameId, String playerName, int position) {
            Room room = rooms.get(gameId);
            return room != null && room.makeMove(playerName, position);
        }

        public void handlePlayerExit(String roomId, String playerName) {
            Room room = rooms.get(roomId);
            if (room != null) {
                room.removePlayer(playerName);
                if (room.shouldBeRemoved()) {
                    rooms.remove(roomId);
                }
            }
        }

        public void removeRoom(String roomId) {
            rooms.remove(roomId);
        }
    }

    static class Room {
        private final String roomId;
        private final String roomName;
        private final List<Player> players = new CopyOnWriteArrayList<>();
        private Game game;
        private String status = "WAITING";

        public Room(String roomId, String roomName) {
            this.roomId = roomId;
            this.roomName = roomName;
        }

        public synchronized void addPlayer(String name, StreamObserver<com.example.tictactoe.GameState> observer) {
            if (players.size() >= 2 || !status.equals("WAITING")) {
                observer.onError(Status.FAILED_PRECONDITION
                        .withDescription("Комната заполнена или игра уже началась")
                        .asRuntimeException());
                return;
            }

            String symbol = players.isEmpty() ? "X" : "O";
            Player newPlayer = new Player(name, symbol, observer);
            players.add(newPlayer);

            com.example.tictactoe.GameState initialState = com.example.tictactoe.GameState.newBuilder()
                    .setGameId(roomId)
                    .setStatus(getStatusMessage())
                    .setPlayersCount(players.size())
                    .setPlayerSymbol(symbol)
                    .addAllBoard(getCurrentBoard())
                    .build();

            try {
                observer.onNext(initialState);
            } catch (Exception e) {
                logger.error("Ошибка отправки состояния", e);
            }

            if (players.size() == 2) {
                startGame();
            }
        }

        private List<String> getCurrentBoard() {
            return game != null ? Arrays.asList(game.getBoard()) : Collections.nCopies(9, "");
        }

        private String getStatusMessage() {
            return switch (status) {
                case "WAITING" -> "Ожидание игроков (" + players.size() + "/2)";
                case "IN_PROGRESS" -> "Сейчас ходит: " + game.getCurrentPlayer();
                case "X_WON" -> "Победил X!";
                case "O_WON" -> "Победил O!";
                case "DRAW" -> "Ничья!";
                case "ABANDONED" -> "Соперник покинул игру";
                default -> "Неизвестный статус";
            };
        }

        private void startGame() {
            this.game = new Game(roomId);
            this.status = "IN_PROGRESS";
            notifyPlayers();
        }

        public boolean makeMove(String playerName, int position) {
            String symbol = getPlayerSymbol(playerName);
            boolean success = symbol != null && game != null && game.makeMove(symbol, position);
            if (success) {
                notifyPlayers();
                if (!game.getStatus().equals("IN_PROGRESS")) {
                    status = game.getStatus();
                    notifyPlayers();
                }
            }
            return success;
        }

        private String getPlayerSymbol(String playerName) {
            return players.stream()
                    .filter(p -> p.name.equals(playerName))
                    .findFirst()
                    .map(p -> p.symbol)
                    .orElse(null);
        }

        private void notifyPlayers() {
            String statusMessage = getStatusMessage();
            List<String> board = getCurrentBoard();

            players.forEach(p -> {
                com.example.tictactoe.GameState state = com.example.tictactoe.GameState.newBuilder()
                        .setGameId(roomId)
                        .addAllBoard(board)
                        .setCurrentPlayer(game != null ? game.getCurrentPlayer() : "")
                        .setStatus(statusMessage)
                        .setPlayersCount(players.size())
                        .setPlayerSymbol(p.symbol)
                        .build();

                try {
                    p.observer.onNext(state);
                } catch (Exception e) {
                    logger.error("Ошибка отправки состояния игроку {}", p.name, e);
                }
            });
        }

        public synchronized void removePlayer(String playerName) {
            players.removeIf(p -> {
                if (p.name.equals(playerName)) {
                    safelyCloseObserver(p.observer);
                    return true;
                }
                return false;
            });

            if (players.isEmpty()) {
                resetRoom();
            } else if (status.equals("IN_PROGRESS")) {
                status = "ABANDONED";
                notifyPlayers();
                resetGame();
            }
        }

        private void safelyCloseObserver(StreamObserver<com.example.tictactoe.GameState> observer) {
            try {
                observer.onCompleted();
            } catch (Exception e) {
                logger.warn("Ошибка закрытия соединения", e);
            }
        }

        private void resetRoom() {
            this.game = null;
            this.status = "CLOSED";
        }

        private void resetGame() {
            this.game = new Game(roomId);
            this.status = "WAITING";
            players.forEach(p -> p.symbol = players.indexOf(p) == 0 ? "X" : "O");
            notifyPlayers();
        }

        public String getStatus() {
            return status;
        }

        public int getPlayersCount() {
            return players.size();
        }

        public String getRoomName() {
            return roomName;
        }

        public boolean shouldBeRemoved() {
            return status.equals("CLOSED") || players.isEmpty();
        }

        static class Player {
            final String name;
            String symbol;
            final StreamObserver<com.example.tictactoe.GameState> observer;

            Player(String name, String symbol, StreamObserver<com.example.tictactoe.GameState> observer) {
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
            Arrays.fill(board, "");
        }

        public synchronized boolean makeMove(String symbol, int position) {
            if (!status.equals("IN_PROGRESS")
                    || position < 0 || position >= 9
                    || !board[position].isEmpty()
                    || !symbol.equals(currentPlayer)) {
                return false;
            }

            board[position] = symbol;
            checkGameStatus();

            if (status.equals("IN_PROGRESS")) {
                currentPlayer = currentPlayer.equals("X") ? "O" : "X";
            }

            return true;
        }

        private void checkGameStatus() {
            String winner = checkWinner();
            if (winner != null) {
                status = winner + "_WON";
            } else if (isBoardFull()) {
                status = "DRAW";
            }
        }

        private String checkWinner() {
            int[][] winCombinations = {
                    {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
                    {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
                    {0, 4, 8}, {2, 4, 6}
            };

            for (int[] combo : winCombinations) {
                String a = board[combo[0]];
                String b = board[combo[1]];
                String c = board[combo[2]];

                if (!a.isEmpty() && a.equals(b) && a.equals(c)) {
                    return a;
                }
            }
            return null;
        }

        private boolean isBoardFull() {
            return Arrays.stream(board).noneMatch(String::isEmpty);
        }

        public String[] getBoard() {
            return Arrays.copyOf(board, 9);
        }

        public String getCurrentPlayer() {
            return currentPlayer;
        }

        public String getStatus() {
            return status;
        }
    }
}