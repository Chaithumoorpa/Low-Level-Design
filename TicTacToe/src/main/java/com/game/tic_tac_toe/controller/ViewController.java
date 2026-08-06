package com.game.tic_tac_toe.controller;

import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import com.game.tic_tac_toe.dto.CreateGameRequest;
import com.game.tic_tac_toe.service.GameService;

@Controller
public class ViewController {

    private final GameService gameService;

    public ViewController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/")
    public String home(Model model) {

        model.addAttribute("gameRequest", new CreateGameRequest());

        return "index";
    }

    @PostMapping("/create")
    public String createGame(@ModelAttribute CreateGameRequest request) {

        UUID gameId = gameService.createGame(
                request.getPlayer1(),
                request.getPlayer2(),
                request.getBoardSize());

        return "redirect:/play/" + gameId;
    }

    @GetMapping("/play/{id}")
    public String play(@PathVariable UUID id, Model model) {

        model.addAttribute("game", gameService.getGame(id));
        model.addAttribute("gameId", id);

        return "game";
    }
    
    @PostMapping("/play/{id}/move")
    public String makeMove(
            @PathVariable UUID id,
            @RequestParam int row,
            @RequestParam int col) {

        gameService.makeMove(id, row, col);

        return "redirect:/play/" + id;
    }
}