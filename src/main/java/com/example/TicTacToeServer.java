package com.example;

import com.example.tictactoe.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TicTacToeServer {
    private final int port;
    private final Server server;
    private final RoomManager roomManager = new RoomManager();

    // Конструктор сервера
    public TicTacToeServer(int port) {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new TicTacToeService(roomManager))
                .build();
    }

    // Запуск сервера
    public void start() throws IOException {
        server.start();
        System.out.println("Server started on port " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutting down server...");
            try {
                server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }));
    }

    // Блокировка основного потока до завершения
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

    // Реализация gRPC сервиса
    static class TicTacToeService extends TicTacToeGrpc.TicTacToeImplBase {
        private final RoomManager roomManager;

        public TicTacToeService(RoomManager roomManager) {
            this.roomManager = roomManager;
        }

        @Override
        public void createRoom(RoomRequest request, StreamObserver<RoomResponse> responseObserver) {
            try {
                RoomResponse response = roomManager.createRoom(
                        request.getRoomName(),
                        request.getPlayerName());
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void listRooms(Empty request, StreamObserver<RoomList> responseObserver) {
            responseObserver.onNext(roomManager.getRoomList());
            responseObserver.onCompleted();
        }

        @Override
        public void joinRoom(RoomRequest request, StreamObserver<GameState> responseObserver) {
            try {
                roomManager.joinRoom(
                        request.getRoomName(),
                        request.getPlayerName(),
                        responseObserver);
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void makeMove(Move request, StreamObserver<MoveResult> responseObserver) {
            try {
                boolean success = roomManager.handleMove(
                        request.getGameId(),
                        request.getPlayerName(),
                        request.getPosition());
                responseObserver.onNext(MoveResult.newBuilder()
                        .setSuccess(success)
                        .setMessage(success ? "Move accepted" : "Invalid move")
                        .build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }

    // Менеджер комнат
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
            rooms.forEach((id, room) -> {
                if (room.getStatus().equals("WAITING")) {
                    builder.addRooms(RoomInfo.newBuilder()
                            .setRoomId(id)
                            .setRoomName(room.getRoomName())
                            .setPlayersCount(room.getPlayersCount())
                            .setStatus(room.getStatus())
                            .build());
                }
            });
            return builder.build();
        }

        public void joinRoom(String roomId, String playerName, StreamObserver<GameState> observer) {
            Room room = rooms.get(roomId);
            if (room == null) {
                observer.onError(Status.NOT_FOUND.withDescription("Room not found").asRuntimeException());
                return;
            }
            room.addPlayer(playerName, observer);
        }

        public boolean handleMove(String gameId, String playerName, int position) {
            Room room = rooms.get(gameId);
            return room != null && room.makeMove(playerName, position);
        }
    }

    // Комната
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

        public synchronized void addPlayer(String name, StreamObserver<GameState> observer) {
            if (players.size() == 2) {
                startGame(); // Переводим комнату в статус IN_PROGRESS
            }

            String symbol = players.isEmpty() ? "X" : "O";
            players.add(new Player(name, symbol, observer));

            if (players.size() == 2) {
                startGame();
            }
            notifyPlayers();
        }

        private void startGame() {
            this.game = new Game(roomId);
            this.status = "IN_PROGRESS";
            notifyPlayers();
        }

        public boolean makeMove(String playerName, int position) {
            String symbol = getPlayerSymbol(playerName);
            return symbol != null && game.makeMove(symbol, position);
        }

        private String getPlayerSymbol(String playerName) {
            for (Player p : players) {
                if (p.name.equals(playerName)) {
                    return p.symbol;
                }
            }
            return null;
        }

        private void notifyPlayers() {
            List<String> boardData = game != null ?
                    Arrays.asList(game.getBoard()) :
                    Collections.nCopies(9, "");

            GameState state = GameState.newBuilder()
                    .setGameId(roomId)
                    .addAllBoard(boardData)
                    .setCurrentPlayer(game != null ? game.getCurrentPlayer() : "WAITING")
                    .setStatus(getStatus())
                    .build();

            players.forEach(p -> p.observer.onNext(state));
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

    // Игра
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
            System.out.println(
                    "Move attempt: " +
                            "symbol=" + symbol + ", " +
                            "position=" + position + ", " +
                            "board=" + Arrays.toString(board)
            );

            if (!status.equals("IN_PROGRESS")) {
                System.out.println("Game is not in progress");
                return false;
            }

            // Проверка валидности позиции
            if (position < 0 || position >= 9) {
                System.out.println("Invalid position: " + position);
                return false;
            }

            // Проверка, свободна ли ячейка
            if (!board[position].isEmpty()) {
                System.out.println("Cell " + position + " is occupied");
                return false;
            }

            // Проверка, правильный ли игрок ходит
            if (!symbol.equals(currentPlayer)) {
                System.out.println("Wrong turn. Current player: " + currentPlayer);
                return false;
            }

            // Совершаем ход
            board[position] = symbol;
            System.out.println("Move accepted: " + symbol + " at " + position);

            // Проверяем победу
            checkGameStatus();

            // Меняем текущего игрока
            currentPlayer = currentPlayer.equals("X") ? "O" : "X";
            return true;
        }

        private void checkGameStatus() {
            String winner = checkWinner();
            if (winner != null) {
                status = winner + "_WON";
                System.out.println("Player " + winner + " wins!");
            } else if (isBoardFull()) {
                status = "DRAW";
                System.out.println("Game ended in a draw");
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
            return Arrays.stream(board).noneMatch(cell -> cell.isEmpty());
        }

        public String[] getBoard() {
            return Arrays.copyOf(board, 9); // Возвращаем копию массива
        }

        public String getCurrentPlayer() {
            return currentPlayer;
        }

        public String getStatus() {
            return status;
        }
    }
}
