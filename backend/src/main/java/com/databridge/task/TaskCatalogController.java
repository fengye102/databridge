package com.databridge.task;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
public class TaskCatalogController {
    private final TaskCatalog catalog;

    public TaskCatalogController(TaskCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public TaskCatalog.Catalog read() {
        return catalog.read();
    }
}
