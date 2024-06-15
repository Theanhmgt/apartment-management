package com.ou.repositories;

import com.ou.pojo.Service;

import java.util.List;

public interface ServiceReposity {
    List<Service> getServices();
    Service getServiceById(int id);
    void addService(Service service);
}
