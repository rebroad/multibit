/**
 * Copyright 2011 multibit.org
 *
 * Licensed under the MIT license (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://opensource.org/licenses/mit-license.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.multibit.viewsystem.swing.action;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;

import org.multibit.controller.MultiBitController;
import org.multibit.model.MultiBitModel;
import org.multibit.viewsystem.swing.view.AbstractTradePanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This {@link Action} represents the action to toggle the more or less button
 * on the send/ receive bitcoin panels
 */
public class MoreOrLessAction extends AbstractAction {

    private static final Logger log = LoggerFactory.getLogger(MoreOrLessAction.class);


    private static final long serialVersionUID = 114352235465057705L;

    private MultiBitController controller;
    private AbstractTradePanel abstractTradePanel;

    /**
     * Creates a new {@link MoreOrLessAction}.
     */
    public MoreOrLessAction(MultiBitController controller, AbstractTradePanel abstractTradePanel) {
        super("");
        this.controller = controller;
        this.abstractTradePanel = abstractTradePanel;
    }

    /**
     * delegate to generic paste address action
     */
    public void actionPerformed(ActionEvent e) {
        boolean showSidePanel = !abstractTradePanel.isShowSidePanel();
        abstractTradePanel.setShowSidePanel(showSidePanel);
        
        log.debug("showSidePanel = " + showSidePanel);

        // put it in the user preferences - will then get loaded when view
        // form loads
        controller.getModel().setUserPreference(MultiBitModel.SHOW_SIDE_PANEL, (new Boolean(showSidePanel)).toString());
        
        // display the side panel (or not)
        abstractTradePanel.displaySidePanel();
    }
}