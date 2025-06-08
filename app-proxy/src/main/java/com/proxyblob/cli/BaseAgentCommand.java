package com.proxyblob.cli;

import com.proxyblob.ApplicationContext;
import lombok.Setter;

@Setter
public abstract class BaseAgentCommand implements Runnable {
    protected ApplicationContext context;
}
