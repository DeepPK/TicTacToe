package com.example;

import com.example.tictactoe.RoomList; //Классы из протофайла
import com.example.tictactoe.TicTacToeGrpc;
import io.grpc.Server; //Стартует сервер и все взаимодействия реализует
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException; //Для отладки
import java.util.*; //По мелочам (мне IDE сказала это добавить)
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TicTacToeServer {
    private final int port;
    private final Server server;
    private final RoomManager roomManager = new RoomManager(); //отвечает за список комнат

    public TicTacToeServer(int port) {
        this.port = port;                                                //Иницилизируем сервер
        this.server = ServerBuilder.forPort(port)
                .addService(new TicTacToeService(roomManager))
                .build();
    }

    public void start() throws IOException {
        server.start(); //Стартуем и отключаем
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination(); //Защищает от дропа
        }
    }

    public static void main(String[] args) throws Exception {//Входная функция
        TicTacToeServer server = new TicTacToeServer(50051);
        server.start();
        server.blockUntilShutdown();
    }

    static class TicTacToeService extends TicTacToeGrpc.TicTacToeImplBase {
        private final RoomManager roomManager;

        public TicTacToeService(RoomManager roomManager) { //Инициализируем манагера
            this.roomManager = roomManager;
        }

        @Override //Когда к нам пришлёт клиент запрос на создание комнаты, то добавляем её.
        public void createRoom(com.example.tictactoe.CreateRoomRequest request, StreamObserver<com.example.tictactoe.RoomResponse> responseObserver) {
            try {
                com.example.tictactoe.RoomResponse response = roomManager.createRoom(request.getRoomName());
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override  //Возвращаем список комнат
        public void listRooms(com.example.tictactoe.Empty request, StreamObserver<com.example.tictactoe.RoomList> responseObserver) {
            responseObserver.onNext(roomManager.getRoomList());
            responseObserver.onCompleted();
        }

        @Override //Манагер добовляет в комнату нового игрока, тригерится клиентом
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

        @Override  //Фиксируем ход игрока у себя и запоминаем
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

        @Override //Удаляем игрока из комнаты, если клиент тригернёт выход
        public void leaveRoom(com.example.tictactoe.LeaveRequest request, StreamObserver<com.example.tictactoe.Empty> responseObserver) {
            roomManager.handlePlayerExit(request.getRoomId(), request.getPlayerName());
            responseObserver.onNext(com.example.tictactoe.Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    static class RoomManager { //Манагер (смешное слово, Manager)
        private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
        private final AtomicInteger roomCounter = new AtomicInteger();

        public com.example.tictactoe.RoomResponse createRoom(String roomName) {
            String roomId = "room-" + roomCounter.incrementAndGet(); // уже тригерится здесь, ставим новую комнату в мапу
            rooms.put(roomId, new Room(roomId, roomName));
            return com.example.tictactoe.RoomResponse.newBuilder()
                    .setSuccess(true)
                    .setRoomId(roomId)
                    .build();
        }

        public com.example.tictactoe.RoomList getRoomList() {  //Все игры, что не пустые и полные мы кидаем в список клиенту
            RoomList.Builder builder = com.example.tictactoe.RoomList.newBuilder();
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

        public void joinRoom(String roomId, String playerName, StreamObserver<com.example.tictactoe.GameState> observer) {//Если комната не удалена, закидываем в неё игрока
            Room room = rooms.get(roomId);
            if (room == null) {
                observer.onError(Status.NOT_FOUND.withDescription("Комната не найдена").asRuntimeException());
                return;
            }
            room.addPlayer(playerName, observer);
        }

        public boolean handleMove(String gameId, String playerName, int position) { //От метода клинта
            Room room = rooms.get(gameId);
            return room != null && room.makeMove(playerName, position); //Ставим в  поле значение символа и возвращаем тру, если успешно
        }

        public void handlePlayerExit(String roomId, String playerName) {  //Продолжение метода клиента
            Room room = rooms.get(roomId);
            if (room != null) {
                room.removePlayer(playerName);   //Если комната есть удаляем игрока, если игроков нет или игра закончилась, удаляем
                if (room.shouldBeRemoved()) {
                    rooms.remove(roomId);
                }
            }
        }
    }

    static class Room {
        private final String roomId;
        private final String roomName;
        private final List<Player> players = new CopyOnWriteArrayList<>();  //Игроки комнаты
        private Game game;                      //Ситуация в игре
        private String status = "WAITING";     //Состояние игры

        public Room(String roomId, String roomName) { //Инициализатор
            this.roomId = roomId;
            this.roomName = roomName;
        }

        public synchronized void addPlayer(String name, StreamObserver<com.example.tictactoe.GameState> observer) {
            String symbol;
            if (players.isEmpty()) //Если игрок только зашёл, то он всегда X
            {
                symbol = "X";
            } else {                                                //Если игрок второй, то нужно дать ему символ противоположный игроку в комнате
                System.out.println(players.getFirst().symbol);
                symbol = Objects.equals(players.getFirst().symbol, "O") ? "X" : "O";
            }
            Player newPlayer = new Player(name, symbol, observer);
            players.add(newPlayer); //Добавляем и грока и отправляем клиенту инфу о состоянии комнаты

            com.example.tictactoe.GameState initialState = com.example.tictactoe.GameState.newBuilder()
                    .setGameId(roomId)
                    .setStatus(getStatusMessage())
                    .setPlayersCount(players.size())
                    .setPlayerSymbol(symbol)
                    .addAllBoard(getCurrentBoard())
                    .build();
            observer.onNext(initialState);

            if (players.size() == 2) { //Если комната полна, начинаем игру
                startGame();
            }
        }

        private List<String> getCurrentBoard() { //Проверяем поле
            return game != null ? Arrays.asList(game.getBoard()) : Collections.nCopies(9, "");
        }

        private String getStatusMessage() { //В зависимости от статуса могут потребоваться разные сообщения для клиентов
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

        private void startGame() {  //Запускает новую игру и обновляет у клиентов
            this.game = new Game(roomId);
            this.status = "IN_PROGRESS";
            notifyPlayers();
        }

        public boolean makeMove(String playerName, int position) { //Фиксирует ход у себя
            String symbol = getPlayerSymbol(playerName);
            boolean success = symbol != null && game != null && game.makeMove(symbol, position);
            if (success) {
                notifyPlayers();  //Если ход успешен, тообновляем пользователей и проверяем, что игра всё ещё идёт
                if (!game.getStatus().equals("IN_PROGRESS")) {
                    status = game.getStatus();
                    notifyPlayers();
                }
            }
            return success;
        }

        private String getPlayerSymbol(String playerName) {      //Узнать символ игрока
            return players.stream()
                    .filter(p -> p.name.equals(playerName))
                    .findFirst()
                    .map(p -> p.symbol)
                    .orElse(null);
        }

        private void notifyPlayers() {         //отправляем игрокам инфу о ситуации на поле и статус игры
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
                p.observer.onNext(state);
            });
        }

        public synchronized void removePlayer(String playerName) {   //Если такой игрок есть, то удаляем
            players.removeIf(p -> {
                if (p.name.equals(playerName)) {
                    safelyCloseObserver(p.observer);
                    return true;
                }
                return false;
            });

            if (players.isEmpty()) {  //Если игроков не осталось, удаляем комнату. Иначе оставшемуся игроку обновляем комнату и ресетим игру
                resetRoom();
            } else if (status.equals("IN_PROGRESS")) {
                status = "ABANDONED";
                notifyPlayers();
                resetGame();
            }
        }

        private void safelyCloseObserver(StreamObserver<com.example.tictactoe.GameState> observer) {    //Безопасно перекидываеи клиента на экран лобби
            observer.onCompleted();
        }

        private void resetRoom() { //нулл игра и она завкрыта
            this.game = null;
            this.status = "CLOSED";
        }

        private void resetGame() {          //Пересоздаём игру
            this.game = new Game(roomId);
            players.getFirst().symbol = "X";   //Оставшийся игрок всегда будет крестиком
            this.status = "WAITING";
            notifyPlayers();        //Обновим ему инфу
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

        public boolean shouldBeRemoved() {    //Проверяет комнату на удаление
            return status.equals("CLOSED") || players.isEmpty();
        }

        static class Player {       //Инфа о игроке
            final String name;
            String symbol;
            final StreamObserver<com.example.tictactoe.GameState> observer; //Передаёт инфу о игре через этот поток

            Player(String name, String symbol, StreamObserver<com.example.tictactoe.GameState> observer) {
                this.name = name;
                this.symbol = symbol;
                this.observer = observer;
            }
        }
    }

    static class Game {
        private String gameID;       //Для вывода в список
        private final String[] board = new String[9];
        private String currentPlayer = "X";        //Первый всегда крестик
        private String status = "IN_PROGRESS";     //Игра начинается всегда в процессе

        public Game(String gameId) {
            this.gameID = gameId;
            Arrays.fill(board, "");
        }

        public synchronized boolean makeMove(String symbol, int position) {   //Добавляем ход игрока на поле
            if (!status.equals("IN_PROGRESS")
                    || position < 0 || position >= 9
                    || !board[position].isEmpty()
                    || !symbol.equals(currentPlayer)) {
                return false;
            }

            board[position] = symbol;
            checkGameStatus();  //Проверка на победу

            if (status.equals("IN_PROGRESS")) {  //Меняем ход игрока
                currentPlayer = currentPlayer.equals("X") ? "O" : "X";
            }
            return true;
        }

        private void checkGameStatus() {        //бъявляем комнату победу, ничья, или продолжаем
            String winner = checkWinner();
            if (winner != null) {    //Если всё такие есть результат
                status = winner + "_WON";
            } else if (isBoardFull()) { //Если ничья
                status = "DRAW";
            }
        }

        private String checkWinner() {  //Проверяет комбы победы
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
            return null;   //Если не нашлась комба, продолжаем играть
        }

        private boolean isBoardFull() {      //Проверка на ничью
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