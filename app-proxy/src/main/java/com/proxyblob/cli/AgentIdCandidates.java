package com.proxyblob.cli;

import com.proxyblob.state.AppState;
import com.proxyblob.dto.ContainerInfo;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class AgentIdCandidates implements Iterable<String> {

    @Override
    public Iterator<String> iterator() {
        try {
            List<ContainerInfo> containers = AppState.getStorageManager().listAgentContainers();
            return containers.stream()
                    .map(ContainerInfo::getId)
                    .iterator();
        } catch (Exception e) {
            return Collections.emptyIterator();
        }
    }
}
