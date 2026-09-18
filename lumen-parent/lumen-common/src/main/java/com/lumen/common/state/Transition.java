package com.lumen.common.state;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.function.Predicate;

@Data @AllArgsConstructor
public class Transition<S, E, C> {
    private S from;
    private E event;
    private S to;
    private Predicate<C> guard;
    private String name;
}