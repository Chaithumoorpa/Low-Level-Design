package com.game.tic_tac_toe.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.game.tic_tac_toe.core.Game;
import com.game.tic_tac_toe.enums.Symbol;
import com.game.tic_tac_toe.model.Player;

@Service
public class GameService {

    private final Map<UUID, Game> games = new ConcurrentHashMap<>();
    
    public UUID createGame(String player1, String player2, int boardSize) {

        Player p1 = new Player(player1, Symbol.X);
        Player p2 = new Player(player2, Symbol.O);

        Game game = new Game(p1, p2, boardSize);

        UUID id = UUID.randomUUID();

        games.put(id, game);

        return id;
    }
    
    public Game getGame(UUID id) {

        Game game = games.get(id);

        if (game == null) {
            throw new RuntimeException("Game not found");
        }

        return game;
    }
    
    public Game makeMove(UUID id, int row, int col) {

        Game game = getGame(id);

        game.makeMove(row, col);

        return game;
    }

}