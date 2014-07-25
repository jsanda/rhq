/*
 * RHQ Management Platform
 * Copyright (C) 2005-2014 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License, version 2, as
 * published by the Free Software Foundation, and/or the GNU Lesser
 * General Public License, version 2.1, also as published by the Free
 * Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License and the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License
 * and the GNU Lesser General Public License along with this program;
 * if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.rhq.core.domain.storage;

import java.io.Serializable;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.PrePersist;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

/**
 * A Storage Maintenance Job
 *
 * @author Stefan Negrea
 */
@Entity(name = "MaintenanceJob")
@NamedQueries( //
{
 @NamedQuery(name = MaintenanceStep.QUERY_FIND_ALL, query = "SELECT s FROM MaintenanceStep s")
})
@SequenceGenerator(allocationSize = org.rhq.core.domain.util.Constants.ALLOCATION_SIZE, name = "RHQ_STORAGE_MAINT_STEP_ID_SEQ", sequenceName = "RHQ_STORAGE_MAINT_STEP_ID_SEQ")
@Table(name = "RHQ_STORAGE_MAINT_STEP")
public class MaintenanceStep implements Serializable {

    public static final long serialVersionUID = 1L;

    public static final String QUERY_FIND_ALL = "MaintenanceStep.findAll";

    @Column(name = "ID", nullable = false)
    @GeneratedValue(strategy = GenerationType.AUTO, generator = "RHQ_STORAGE_MAINT_STEP_ID_SEQ")
    @Id
    private int id;

    @JoinColumn(name = "STORAGE_MAINT_JOB_ID", referencedColumnName = "ID", nullable = true)
    @ManyToOne
    private MaintenanceJob maintenanceJob;

    @Column(name = "STEP", nullable = false)
    private int step;

    @Column(name = "NODE_ADDRESS", nullable = false)
    private String nodeAddress;

    //TODO: might have to drop if type is enough
    @Column(name = "NAME", nullable = false)
    private String name;

    //TODO: have to change to full Enum once the types settle to
    //      a finite few
    @Column(name = "TYPE", nullable = false)
    @Enumerated(EnumType.STRING)
    private Type type;

    // the time this maintenance workflow was created
    @Column(name = "CTIME", nullable = false)
    private long ctime;

    // the time this maintenance workflow was last updated
    @Column(name = "MTIME", nullable = false)
    private long mtime;

    // sequential operation
    @Column(name = "SEQUENTIAL", nullable = false)
    private boolean sequential;

    // step timeout
    @Column(name = "TIMEOUT", nullable = false)
    private long timeout;

    @Column(name = "ARGS", nullable = false)
    private String args;

    @Column(name = "ON_FAILURE", nullable = false)
    private String onFailure;

    // required for JPA
    public MaintenanceStep() {
    }

    public int getId() {
        return id;
    }

    public MaintenanceStep setId(int id) {
        this.id = id;
        return this;
    }

    public MaintenanceJob getMaintenanceJob() {
        return maintenanceJob;
    }

    public MaintenanceStep setMaintenanceJob(MaintenanceJob maintenanceJob) {
        this.maintenanceJob = maintenanceJob;
        return this;
    }

    public int getStep() {
        return step;
    }

    public MaintenanceStep setStep(int step) {
        this.step = step;
        return this;
    }

    public String getNodeAddress() {
        return nodeAddress;
    }

    public MaintenanceStep setNodeAddress(String nodeAddress) {
        this.nodeAddress = nodeAddress;
        return this;
    }

    public String getOperationName() {
        return name;
    }

    public MaintenanceStep setName(String name) {
        this.name = name;
        return this;
    }

    public Type getType() {
        return type;
    }

    public MaintenanceStep setType(Type type) {
        this.type = type;
        return this;
    }

    public long getCtime() {
        return ctime;
    }

    public long getMtime() {
        return mtime;
    }

    public MaintenanceStep setMtime(long mtime) {
        this.mtime = mtime;
        return this;
    }

    public boolean isSequential() {
        return sequential;
    }

    public MaintenanceStep setSequential(boolean sequential) {
        this.sequential = sequential;
        return this;
    }

    public long getTimeout() {
        return timeout;
    }

    public MaintenanceStep setTimeout(long timeout) {
        this.timeout = timeout;
        return this;
    }

    public String getArgs() {
        return args;
    }

    public MaintenanceStep setArgs(String args) {
        this.args = args;
        return this;
    }

    public String getOnFailure() {
        return onFailure;
    }

    public MaintenanceStep setOnFailure(String onFailure) {
        this.onFailure = onFailure;
        return this;
    }

    @Override
    public String toString() {
        return "MaintenanceStep[id=" + id + ", name=" + name + ", Type=" + type
            + ", ctime=" + ctime + "]";
    }

    @PrePersist
    void onPersist() {
        this.ctime = System.currentTimeMillis();
        this.mtime = this.ctime;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode()) + ((type == null) ? 0 : type.hashCode())
            + step + ((nodeAddress == null) ? 0 : nodeAddress.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || !(obj instanceof MaintenanceStep)) {
            return false;
        }

        final MaintenanceStep other = (MaintenanceStep) obj;


        if (getId() != other.getId() || getCtime() != other.getCtime()) {
            return false;
        }

        return true;
    }

    public enum Type {
        // I think we only need two types - resource operation and server operation. See
        // the @ServerOperation annotation in the storage-maintenance module for more
        // details on server operations.
        RESOURCE_OP, SERVER_OP

//        ResourceUpdate,
//        EntityUpdate,
//        ResourceOperation,
//        ServerUpdate
    }
}
