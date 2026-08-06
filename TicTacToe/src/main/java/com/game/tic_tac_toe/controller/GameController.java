package com.game.tic_tac_toe.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.game.tic_tac_toe.core.Game;
import com.game.tic_tac_toe.dto.CreateGameRequest;
import com.game.tic_tac_toe.dto.MoveRequest;
import com.game.tic_tac_toe.service.GameService;

@RestController
@RequestMapping("/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
        
    }
    
    @PostMapping
    public UUID createGame(@RequestBody CreateGameRequest request) {

        return gameService.createGame(
                request.getPlayer1(),
                request.getPlayer2(),
        		request.getBoardSize());
    }
    
    @PostMapping("/{id}/move")
    public Game makeMove(
            @PathVariable UUID id,
            @RequestBody MoveRequest request) {

        return gameService.makeMove(
                id,
                request.getRow(),
                request.getCol());
    }
    
    @GetMapping("/{id}")
    public Game getGame(@PathVariable UUID id) {

        return gameService.getGame(id);
    }

}