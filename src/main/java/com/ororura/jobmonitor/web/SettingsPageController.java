package com.ororura.jobmonitor.web;

import com.ororura.jobmonitor.collection.CollectionProperties;
import com.ororura.jobmonitor.source.PlaywrightProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SettingsPageController {
  private final CollectionProperties collection;
  private final PlaywrightProperties playwright;
  private final String database;

  SettingsPageController(
      CollectionProperties collection,
      PlaywrightProperties playwright,
      HikariDataSource dataSource) {
    this.collection = collection;
    this.playwright = playwright;
    this.database = dataSource.getJdbcUrl().substring("jdbc:sqlite:".length());
  }

  @GetMapping("/settings")
  String settings(Model model) {
    model.addAttribute("collection", collection);
    model.addAttribute("playwright", playwright);
    model.addAttribute("database", database);
    return "settings";
  }
}
