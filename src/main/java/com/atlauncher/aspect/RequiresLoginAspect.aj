package com.atlauncher.aspect;

import com.atlauncher.App;
import com.atlauncher.annot.RequiresLogin;
import com.atlauncher.data.Language;

import javax.swing.JOptionPane;

public aspect RequiresLoginAspect{
    pointcut pubMeth() : execution(public * * (..));

    before(RequiresLogin ann) : pubMeth() && @annotation(ann){
        if(App.settings.getAccount() == null){
            String[] optionss = {Language.INSTANCE.localize("common.ok")};
            JOptionPane.showOptionDialog(App.settings.getParent(),
                                         Language.INSTANCE.localize("instance.cantupdate"),
                                         Language.INSTANCE.localize("instance.noaccountselected"),
                                         JOptionPane.DEFAULT_OPTION,
                                         JOptionPane.ERROR_MESSAGE,
                                         null,
                                         optionss,
                                         optionss[0]
            );
            return;
        }
    }
}