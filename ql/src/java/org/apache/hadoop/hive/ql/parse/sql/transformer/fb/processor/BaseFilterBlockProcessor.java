/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.parse.sql.transformer.fb.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.antlr33.runtime.tree.CommonTree;
import org.apache.hadoop.hive.ql.parse.sql.PantheraExpParser;
import org.apache.hadoop.hive.ql.parse.sql.SqlXlateException;
import org.apache.hadoop.hive.ql.parse.sql.SqlXlateUtil;
import org.apache.hadoop.hive.ql.parse.sql.TranslateContext;
import org.apache.hadoop.hive.ql.parse.sql.transformer.QueryInfo.Column;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.FilterBlock;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.FilterBlockContext;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.FilterBlockUtil;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.PLSQLFilterBlockFactory;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.QueryBlock;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.SubQFilterBlock;

import br.com.porcelli.parser.plsql.PantheraParser_PLSQLParser;


public abstract class BaseFilterBlockProcessor implements FilterBlockProcessor {

  QueryBlock bottomQuery;
  QueryBlock topQuery;
  SubQFilterBlock subQ;

  // original node reference
  CommonTree originalTopSelect;
  CommonTree originalBottomSelect;

  // clone node reference
  CommonTree topSelect;
  CommonTree bottomSelect;

  CommonTree subQNode;
  FilterBlockContext fbContext;
  FilterBlock fb;// current normalFilterBlock
  TranslateContext context;
  Map<String, String> tableAliasMap = new HashMap<String, String>();// <tableName,tableAlias>
  // <tableAlias,<columnNam,columnAliase>> TODO how to do duplicated columnName?
  Map<String, Map<String, String>> columnAliasMap = new HashMap<String, Map<String, String>>();
  Boolean hasNotEqualCorrelated = false;
  CommonTree joinTypeNode;

  /**
   * template method
   */
  @Override
  public void process(FilterBlockContext fbContext, FilterBlock fb, TranslateContext context)
      throws SqlXlateException {
    bottomQuery = fbContext.getQueryStack().pop();
    bottomQuery.setAggregationList(null);// TODO naive
    topQuery = fbContext.getQueryStack().peek();
    originalBottomSelect = bottomQuery.getASTNode();
    originalTopSelect = topQuery.getASTNode();
    fbContext.getQueryStack().push(bottomQuery);
    subQ = fbContext.getSubQStack().peek();
    subQ.setTransformed();
    topSelect = topQuery.cloneSimpleQuery();
    fbContext.setLogicTopSelect(topSelect);
    bottomSelect = bottomQuery.cloneWholeQuery();
    subQNode = subQ.getASTNode();
    this.fbContext = fbContext;
    this.fb = fb;
    this.context = context;
    preProcessAsterisk();
    processFB();
    this.rebuildColumnAlias(topSelect);
    fb.setTransformedNode(topSelect);
  }

  abstract void processFB() throws SqlXlateException;

  /**
   * add column for top query which is select count(*) or *
   */
  void preProcessAsterisk() {
    CommonTree selectList = (CommonTree) topSelect
        .getFirstChildWithType(PantheraExpParser.SELECT_LIST);
    if (selectList != null && selectList.getChildCount() > 0) {
      return;
    }
    List<Column> columnList = fbContext.getqInfo()
        .getRowInfo(
            (CommonTree) originalTopSelect
                .getFirstChildWithType(PantheraExpParser.SQL92_RESERVED_FROM));
    if (columnList != null && columnList.size() > 0) {
      if (selectList == null) {
        selectList = FilterBlockUtil.createSqlASTNode(PantheraExpParser.SELECT_LIST, "SELECT_LIST");
        topSelect.addChild(selectList);
      }
      for (Column column : columnList) {
        CommonTree selectItem = FilterBlockUtil.createSqlASTNode(PantheraExpParser.SELECT_ITEM,
            "SELECT_ITEM");
        selectList.addChild(selectItem);
        CommonTree expr = FilterBlockUtil.createSqlASTNode(PantheraExpParser.EXPR, "EXPR");
        selectItem.addChild(expr);
        CommonTree cascatedElement = FilterBlockUtil.createSqlASTNode(
            PantheraExpParser.CASCATED_ELEMENT, "CASCATED_ELEMENT");
        expr.addChild(cascatedElement);
        CommonTree anyElement = FilterBlockUtil.createSqlASTNode(PantheraExpParser.ANY_ELEMENT,
            "ANY_ELEMENT");
        cascatedElement.addChild(anyElement);
        CommonTree tableName = FilterBlockUtil.createSqlASTNode(PantheraExpParser.ID, column
            .getTblAlias());
        anyElement.addChild(tableName);
        CommonTree columnName = FilterBlockUtil.createSqlASTNode(PantheraExpParser.ID, column
            .getColAlias());
        anyElement.addChild(columnName);
      }
    }
  }

  /**
   * Create TABLE_REF_ELEMENT & attach select node to it.
   *
   * @param select
   * @return alias
   */
  CommonTree createTableRefElement(CommonTree select) {

    CommonTree tableRefElement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.TABLE_REF_ELEMENT, "TABLE_REF_ELEMENT");
    CommonTree viewAlias = FilterBlockUtil.createAlias(context);
    this.buildTableAliasMap(viewAlias.getChild(0).getText(), FilterBlockUtil.getTableName(select));
    FilterBlockUtil.attachChild(tableRefElement, viewAlias);
    CommonTree tableExpression = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.TABLE_EXPRESSION, "TABLE_EXPRESSION");
    FilterBlockUtil.attachChild(tableRefElement, tableExpression);
    CommonTree selectMode = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SELECT_MODE, "SELECT_MODE");
    FilterBlockUtil.attachChild(tableExpression, selectMode);
    CommonTree selectStatement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SELECT_STATEMENT, "SELECT_STATEMENT");
    FilterBlockUtil.attachChild(selectMode, selectStatement);
    CommonTree subQuery = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.SUBQUERY,
        "SUBQUERY");
    FilterBlockUtil.attachChild(selectStatement, subQuery);
    FilterBlockUtil.attachChild(subQuery, select);
    return tableRefElement;
  }


  CommonTree createOpBranch(CommonTree viewAlias, CommonTree colAlias) {
    CommonTree subQ = FilterBlockUtil.cloneTree(subQNode);
    for (int i = 0; i < subQ.getChildCount(); i++) {
      if (subQ.getChild(i).getType() == PantheraParser_PLSQLParser.SUBQUERY) {
        subQ.setChild(i, this.createCascatedElement((CommonTree) viewAlias.getChild(0),
            (CommonTree) colAlias.getChild(0)));
      }

    }
    return subQ;
  }

  void buildWhereBranch(CommonTree viewAlias, CommonTree colAlias) {
    CommonTree where = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_WHERE, "where");
    CommonTree logicExpr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.LOGIC_EXPR,
        "LOGIC_EXPR");
    FilterBlockUtil.attachChild(where, logicExpr);
    FilterBlockUtil.attachChild(logicExpr, this.createOpBranch(viewAlias, colAlias));
    FilterBlockUtil.attachChild(topSelect, where);
  }

  /**
   * create join node & attach to tree
   */
  CommonTree createJoin(CommonTree select) {
    CommonTree from = (CommonTree) select
        .getFirstChildWithType(PantheraParser_PLSQLParser.SQL92_RESERVED_FROM);
    CommonTree join = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.JOIN_DEF, "join");
    FilterBlockUtil.attachChild((CommonTree) from.getChild(0), join);
    return join;
  }

  /**
   * build join node's children without process detail.
   *
   * @param joinTypeNode
   *          join type
   * @return join sub query alias
   */
  CommonTree buildJoin(CommonTree joinTypeNode, CommonTree join, CommonTree select) {
    if (joinTypeNode != null) {
      FilterBlockUtil.attachChild(join, joinTypeNode);
    }
    CommonTree tableRefElement = this.createTableRefElement(select);
    FilterBlockUtil.attachChild(join, tableRefElement);

    return (CommonTree) tableRefElement.getFirstChildWithType(PantheraParser_PLSQLParser.ALIAS);
  }

  /**
   * check bottom select's from table alias. If no alias, create it. FIXME if from join, it's wrong.
   *
   * @return
   */
  @Deprecated
  CommonTree checkFromAlias() throws SqlXlateException {
    CommonTree from = (CommonTree) bottomSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SQL92_RESERVED_FROM);
    if (from.getChild(0).getChildCount() > 1) {
      throw new SqlXlateException("FIXME if from join, it's wrong.");
    }
    CommonTree tableRefElement = (CommonTree) from.getChild(0).getChild(0);
    if (tableRefElement.getChildCount() > 1) {
      return (CommonTree) tableRefElement.getChild(0);
    }
    CommonTree alias = FilterBlockUtil.createAlias(context);
    SqlXlateUtil.addCommonTreeChild(tableRefElement, 0, alias);
    return alias;

  }

  /**
   * extract join key from filter op node. TODO optimize it.
   *
   * @return false: bottom keys<br>
   *         true: top key
   * @throws SqlXlateException
   */
  Map<Boolean, List<CommonTree>> getFilter(CommonTree filterOp) throws SqlXlateException {

    Map<Boolean, List<CommonTree>> result = new HashMap<Boolean, List<CommonTree>>();
    Stack<CommonTree> selectStack = new Stack<CommonTree>();
    selectStack.push(originalTopSelect);
    selectStack.push(originalBottomSelect);
    for (int i = 0; i < filterOp.getChildCount(); i++) {
      CommonTree child = (CommonTree) filterOp.getChild(i);
      if (!PLSQLFilterBlockFactory.getInstance().isCorrelated(this.fbContext.getqInfo(),
          selectStack, child)) {
        if (child.getType() == PantheraParser_PLSQLParser.CASCATED_ELEMENT
            || FilterBlockUtil.findOnlyNode(child, PantheraExpParser.CASCATED_ELEMENT) != null) {
          List<CommonTree> uncorrelatedList = result.get(false);
          if (uncorrelatedList == null) {
            uncorrelatedList = new ArrayList<CommonTree>();
            result.put(false, uncorrelatedList);
          }
          uncorrelatedList.add(child);
        }
      } else {
        List<CommonTree> correlatedList = result.get(true);
        if (correlatedList == null) {
          correlatedList = new ArrayList<CommonTree>();
          result.put(true, correlatedList);
        }
        correlatedList.add(child);
        if (filterOp.getType() != PantheraParser_PLSQLParser.EQUALS_OP) {
          this.hasNotEqualCorrelated = true;
          Map<CommonTree, List<CommonTree>> joinMap = (Map<CommonTree, List<CommonTree>>) this.context
              .getBallFromBasket(TranslateContext.JOIN_TYPE_NODE_BALL);
          if (joinMap != null) {
            List<CommonTree> notEqualConditionList = joinMap.get(joinTypeNode);
            if (notEqualConditionList != null) {
              notEqualConditionList.add(filterOp);
            }
          }
        }
      }
    }

    return result;

  }

  /**
   *
   * @return
   * @throws SqlXlateException
   */
  List<Map<Boolean, List<CommonTree>>> getFilterkey() throws SqlXlateException {
    List<Map<Boolean, List<CommonTree>>> result = new ArrayList<Map<Boolean, List<CommonTree>>>();
    this.getWhereKey(fb.getASTNode(), result);
    return result;
  }

  private void getWhereKey(CommonTree filterOp, List<Map<Boolean, List<CommonTree>>> list)
      throws SqlXlateException {
    if (filterOp == null) {
      return;
    }
    if (FilterBlockUtil.isFilterOp(filterOp)) {
      list.add(getFilter(filterOp));
      return;
    } else if (FilterBlockUtil.isLogicOp(filterOp)) {
      for (int i = 0; i < filterOp.getChildCount(); i++) {
        getWhereKey((CommonTree) filterOp.getChild(i), list);
      }
    } else {
      throw new SqlXlateException("unknow filter operation:" + filterOp.getText());
    }

  }


  /**
   * add cascatedElement(or standardFunction...) branch to bottom SELECT_LIST node
   *
   * @param cascatedElement
   * @return alias
   */
  CommonTree addSelectItem(CommonTree selectList, CommonTree cascatedElement) {
    if (cascatedElement == null || cascatedElement.getChildren() == null) {
      return cascatedElement;
    }
    if (cascatedElement.getChild(0).getChildCount() == 2
        && cascatedElement.getChild(0).getType() == PantheraParser_PLSQLParser.ANY_ELEMENT) {
      // TODO just for tpch 20.sql & 21.sql
      cascatedElement.getChild(0).deleteChild(0);
    }
    CommonTree selectItem = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SELECT_ITEM, "SELECT_ITEM");
    FilterBlockUtil.attachChild(selectList, selectItem);
    CommonTree expr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.EXPR, "EXPR");
    FilterBlockUtil.attachChild(selectItem, expr);
    FilterBlockUtil.attachChild(expr, cascatedElement);
    return this.addAlias(selectItem);
  }

  CommonTree addAlias(CommonTree node) {
    CommonTree alias;
    alias = (CommonTree) node.getFirstChildWithType(PantheraParser_PLSQLParser.ALIAS);
    if (alias == null) {
      alias = FilterBlockUtil.createAlias(context);
      FilterBlockUtil.attachChild(node, alias);
    }
    return alias;
  }


  void buildGroup(CommonTree cascatedElement) {
    if (cascatedElement.getChild(0).getChildCount() == 2) {// FIXME just for tpch 20.sql
      cascatedElement.getChild(0).deleteChild(0);
    }
    CommonTree group = (CommonTree) bottomSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SQL92_RESERVED_GROUP);
    if (group == null) {
      group = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.SQL92_RESERVED_GROUP,
          "group");
      FilterBlockUtil.attachChild(bottomSelect, group);
    }
    CommonTree groupByElement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.GROUP_BY_ELEMENT, "GROUP_BY_ELEMENT");
    FilterBlockUtil.attachChild(group, groupByElement);
    CommonTree expr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.EXPR, "EXPR");
    FilterBlockUtil.attachChild(groupByElement, expr);
    FilterBlockUtil.attachChild(expr, cascatedElement);
  }



  /**
   * build on branch
   *
   * @param op
   * @param child0
   * @param child1
   * @return on node
   */
  CommonTree buildOn(CommonTree op, CommonTree child0, CommonTree child1) {
    CommonTree on = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.SQL92_RESERVED_ON,
        "on");
    FilterBlockUtil.attachChild(on, this.createLogicExpr(op, child0, child1));
    return on;
  }

  CommonTree buildWhere(CommonTree op, CommonTree child0, CommonTree child1) {
    CommonTree where = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_WHERE, "where");
    FilterBlockUtil.attachChild(where, this.createLogicExpr(op, child0, child1));
    return where;
  }

  CommonTree buildWhere(CommonTree op, List<CommonTree> leftChildren, List<CommonTree> rightChildren)
      throws SqlXlateException {
    if (leftChildren == null || leftChildren.size() != rightChildren.size()
        || leftChildren.size() == 0) {
      throw new SqlXlateException("illegal condition.");
    }
    CommonTree where = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_WHERE, "where");
    CommonTree logicExpr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.LOGIC_EXPR,
        "LOGIC_EXPR");
    FilterBlockUtil.attachChild(where, logicExpr);
    CommonTree currentBranch = logicExpr;
    for (int i = 0; i < leftChildren.size(); i++) {
      if (logicExpr.getChildCount() > 0) {
        CommonTree and = FilterBlockUtil.createSqlASTNode(
            PantheraParser_PLSQLParser.SQL92_RESERVED_AND, "and");
        FilterBlockUtil.attachChild(and, (CommonTree) logicExpr.deleteChild(0));
        FilterBlockUtil.attachChild(logicExpr, and);
        currentBranch = and;
      }
      CommonTree operation = FilterBlockUtil.cloneTree(op);
      FilterBlockUtil.attachChild(operation, this.createCascatedElement((CommonTree) leftChildren
          .get(i).getChild(0)));
      FilterBlockUtil.attachChild(operation, this.createCascatedElement((CommonTree) rightChildren
          .get(i).getChild(0)));
      FilterBlockUtil.attachChild(currentBranch, operation);
    }
    return where;
  }

  CommonTree createLogicExpr(CommonTree op, CommonTree child0, CommonTree child1) {
    CommonTree logicExpr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.LOGIC_EXPR,
        "LOGIC_EXPR");
    FilterBlockUtil.attachChild(logicExpr, op);
    FilterBlockUtil.attachChild(op, child0);
    FilterBlockUtil.attachChild(op, child1);
    return logicExpr;
  }

  void addConditionToWhere(CommonTree where, CommonTree op, CommonTree child0, CommonTree child1) {
    CommonTree logicExpr = (CommonTree) where
        .getFirstChildWithType(PantheraParser_PLSQLParser.LOGIC_EXPR);
    assert (logicExpr.getChildCount() == 1);
    CommonTree current = (CommonTree) logicExpr.deleteChild(0);
    CommonTree and = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_AND, "and");
    FilterBlockUtil.attachChild(logicExpr, and);
    FilterBlockUtil.attachChild(and, current);
    FilterBlockUtil.attachChild(op, child0);
    FilterBlockUtil.attachChild(op, child1);
    FilterBlockUtil.attachChild(and, op);
  }




  /**
   * add alias for every column & build column alias map
   *
   * @param alias
   *          table alias node
   * @param selectList
   * @return
   */
  List<CommonTree> buildSelectListAlias(CommonTree alias, CommonTree selectList) {
    List<CommonTree> aliasList = new ArrayList<CommonTree>();
    String aliasName = alias == null ? "" : alias.getChild(0).getText();
    Map<String, String> columnMap = this.columnAliasMap.get(aliasName);
    if (columnMap == null) {
      columnMap = new HashMap<String, String>();
      this.columnAliasMap.put(aliasName, columnMap);
    }
    for (int i = 0; i < selectList.getChildCount(); i++) {
      CommonTree selectItem = (CommonTree) selectList.getChild(i);
      CommonTree columnAlias;
      if (selectItem.getChildCount() > 1) {// had alias
        columnAlias = (CommonTree) selectItem.getChild(1);
      } else {
        columnAlias = this.addAlias(selectItem);
      }
      aliasList.add(columnAlias);
      CommonTree anyElement = (CommonTree) selectItem.getChild(0).getChild(0).getChild(0);
      String columnName;
      if (anyElement == null || anyElement.getType() != PantheraParser_PLSQLParser.ANY_ELEMENT) {
        continue;
      }
      if (anyElement.getChildCount() == 2) {
        columnName = anyElement.getChild(1).getText();
      } else {
        columnName = anyElement.getChild(0).getText();
      }
      String columnAliasName = columnAlias.getChild(0).getText();
      columnMap.put(columnName, columnAliasName);
    }
    this.rebuildGroupOrder(alias);
    return aliasList;
  }

  /**
   * clone subQFilterBlock's non-sub-query branch
   *
   * @return
   * @deprecated
   */
  @Deprecated
  CommonTree cloneSubQOpElement() {
    for (int i = 0; i < subQNode.getChildCount(); i++) {
      if (subQNode.getChild(i).getType() != PantheraParser_PLSQLParser.SUBQUERY) {
        return FilterBlockUtil.cloneTree((CommonTree) subQNode.getChild(i));
      }
    }
    return null;
  }

  CommonTree getSubQOpElement() {
    for (int i = 0; i < subQNode.getChildCount(); i++) {
      if (subQNode.getChild(i).getType() != PantheraParser_PLSQLParser.SUBQUERY) {
        return (CommonTree) subQNode.getChild(i);
      }
    }
    return null;
  }

  void rebuildSubQOpElement(CommonTree subQOpElement, CommonTree columnAlias) {
    CommonTree anyElement = FilterBlockUtil.findOnlyNode(subQOpElement,
        PantheraParser_PLSQLParser.ANY_ELEMENT);
    if (anyElement == null) {// count(*)
      anyElement = FilterBlockUtil.createSqlASTNode(PantheraExpParser.ANY_ELEMENT, "ANY_ELEMENT");
    } else {
      int count = anyElement.getChildCount();
      for (int i = 0; i < count; i++) {
        anyElement.deleteChild(0);
      }
    }
    FilterBlockUtil.attachChild(anyElement, FilterBlockUtil.cloneTree((CommonTree) columnAlias
        .getChild(0)));
    CommonTree cascatedElement = (CommonTree) anyElement.getParent();
    if (cascatedElement == null) {// count(*)
      cascatedElement = FilterBlockUtil.createSqlASTNode(PantheraExpParser.CASCATED_ELEMENT,
          "CASCATED_ELEMENT");
      cascatedElement.addChild(anyElement);
    }
    int index = subQOpElement.childIndex;
    subQNode.deleteChild(index);
    SqlXlateUtil.addCommonTreeChild(subQNode, index, cascatedElement);
  }

  CommonTree createClosingSelect(CommonTree tebleRefElement) {
    CommonTree select = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_SELECT, "select");
    CommonTree from = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_FROM, "from");
    FilterBlockUtil.attachChild(select, from);
    CommonTree tableRef = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.TABLE_REF,
        "TABLE_REF");
    FilterBlockUtil.attachChild(from, tableRef);
    FilterBlockUtil.attachChild(tableRef, tebleRefElement);
    return select;
  }

  CommonTree createCascatedElement(CommonTree child) {
    CommonTree cascatedElement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.CASCATED_ELEMENT, "CASCATED_ELEMENT");
    CommonTree anyElement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.ANY_ELEMENT, "ANY_ELEMENT");
    FilterBlockUtil.attachChild(cascatedElement, anyElement);
    FilterBlockUtil.attachChild(anyElement, child);
    return cascatedElement;
  }

  /**
   * TODO duplicated with below: CommonTree createCascatedElement(CommonTree child1, CommonTree
   * child2
   *
   * @param tableName
   * @param child
   * @return
   */
  CommonTree createCascatedElementWithTableName(CommonTree tableName, CommonTree child) {
    CommonTree cascatedElement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.CASCATED_ELEMENT, "CASCATED_ELEMENT");
    CommonTree anyElement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.ANY_ELEMENT, "ANY_ELEMENT");
    FilterBlockUtil.attachChild(cascatedElement, anyElement);
    FilterBlockUtil.attachChild(anyElement, tableName);
    FilterBlockUtil.attachChild(anyElement, child);
    return cascatedElement;
  }

  CommonTree createCascatedElement(CommonTree child1, CommonTree child2) {
    CommonTree cascatedElement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.CASCATED_ELEMENT, "CASCATED_ELEMENT");
    CommonTree anyElement = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.ANY_ELEMENT, "ANY_ELEMENT");
    FilterBlockUtil.attachChild(cascatedElement, anyElement);
    FilterBlockUtil.attachChild(anyElement, child1);
    FilterBlockUtil.attachChild(anyElement, child2);
    return cascatedElement;
  }

  CommonTree createSelectListForClosingSelect(List<CommonTree> aliasList) {
    CommonTree selectList = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SELECT_LIST, "SELECT_LIST");
    for (CommonTree alias : aliasList) {
      CommonTree newAlias = this.addSelectItem(selectList, this
          .createCascatedElement((CommonTree) alias.getChild(0)));
      this.reRebuildGroupOrder(alias.getChild(0).getText(), newAlias.getChild(0).getText());
    }
    return selectList;
  }

  /**
   * rebuild select list of bottom select to collect_set function.
   */
  void rebuildCollectSet() {
    CommonTree selectList = (CommonTree) bottomSelect
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST);
    for (int i = 0; i < selectList.getChildCount(); i++) {
      CommonTree expr = (CommonTree) selectList.getChild(i).getChild(0);
      CommonTree element = (CommonTree) expr.deleteChild(0);
      CommonTree cascatedElement = FilterBlockUtil.createSqlASTNode(
          PantheraParser_PLSQLParser.CASCATED_ELEMENT, "CASCATED_ELEMENT");
      FilterBlockUtil.attachChild(expr, cascatedElement);
      CommonTree routineCall = FilterBlockUtil.createSqlASTNode(
          PantheraParser_PLSQLParser.ROUTINE_CALL, "ROUTINE_CALL");
      FilterBlockUtil.attachChild(cascatedElement, routineCall);
      CommonTree routineName = FilterBlockUtil.createSqlASTNode(
          PantheraParser_PLSQLParser.ROUTINE_NAME, "ROUTINE_NAME");
      FilterBlockUtil.attachChild(routineCall, routineName);
      CommonTree collectSet = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.ID,
          "collect_set");
      FilterBlockUtil.attachChild(routineName, collectSet);
      CommonTree arguments = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.ARGUMENTS,
          "ARGUMENTS");
      FilterBlockUtil.attachChild(routineCall, arguments);
      CommonTree arguement = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.ARGUMENT,
          "ARGUMENT");
      FilterBlockUtil.attachChild(arguments, arguement);
      CommonTree newExpr = FilterBlockUtil
          .createSqlASTNode(PantheraParser_PLSQLParser.EXPR, "EXPR");
      FilterBlockUtil.attachChild(arguement, newExpr);
      FilterBlockUtil.attachChild(newExpr, element);
    }
  }

  /**
   * rebuild LOGIC_EXPR branch to array_contains function.
   *
   * @param logicExpr
   * @throws SqlXlateException
   */
  void rebuildArrayContains(CommonTree logicExpr) throws SqlXlateException {
    for (int k = 0; k < logicExpr.getChildCount(); k++) {
      if (logicExpr.getChild(k).getType() == PantheraParser_PLSQLParser.SQL92_RESERVED_AND) {
        this.rebuildArrayContains((CommonTree) logicExpr.getChild(k));
      } else {
        CommonTree op = (CommonTree) logicExpr.deleteChild(k);

        CommonTree newOp;
        switch (op.getType()) {
        case PantheraParser_PLSQLParser.NOT_IN:
          newOp = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.SQL92_RESERVED_NOT,
              "not");
          // this.attachChild(logicExpr, newOp);
          SqlXlateUtil.addCommonTreeChild(logicExpr, k, newOp);
          break;
        default:
          throw new SqlXlateException("UnProcess logic operator." + op.getText());
        }
        CommonTree cascatedElement = FilterBlockUtil.createSqlASTNode(
            PantheraParser_PLSQLParser.CASCATED_ELEMENT, "CASCATED_ELEMENT");
        FilterBlockUtil.attachChild(newOp, cascatedElement);
        CommonTree routineCall = FilterBlockUtil.createSqlASTNode(
            PantheraParser_PLSQLParser.ROUTINE_CALL, "ROUTINE_CALL");
        FilterBlockUtil.attachChild(cascatedElement, routineCall);
        CommonTree routineName = FilterBlockUtil.createSqlASTNode(
            PantheraParser_PLSQLParser.ROUTINE_NAME, "ROUTINE_NAME");
        FilterBlockUtil.attachChild(routineCall, routineName);
        CommonTree arrayContains = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.ID,
            "array_contains");
        FilterBlockUtil.attachChild(routineName, arrayContains);
        CommonTree arguments = FilterBlockUtil.createSqlASTNode(
            PantheraParser_PLSQLParser.ARGUMENTS, "ARGUMENTS");
        FilterBlockUtil.attachChild(routineCall, arguments);
        for (int i = 0; i < op.getChildCount(); i++) {
          CommonTree arguement = FilterBlockUtil.createSqlASTNode(
              PantheraParser_PLSQLParser.ARGUMENT, "ARGUMENT");
          FilterBlockUtil.attachChild(arguments, arguement);
          CommonTree expr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.EXPR,
              "EXPR");
          FilterBlockUtil.attachChild(arguement, expr);
          CommonTree element = (CommonTree) op.getChild(i);
          FilterBlockUtil.attachChild(expr, element);
        }

      }
    }

  }

  CommonTree createMinus(CommonTree select) {
    CommonTree minus = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.MINUS_SIGN,
        "minus");
    CommonTree subq = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.SUBQUERY,
        "SUBQUERY");
    FilterBlockUtil.attachChild(minus, subq);
    FilterBlockUtil.attachChild(subq, select);
    return minus;
  }

  private void buildTableAliasMap(String alias, Set<String> tableNames) {
    for (String tableName : tableNames) {
      this.tableAliasMap.put(tableName, alias);
    }
  }

  CommonTree rebuildCascatedElement(CommonTree cascatedElement) {
    CommonTree anyElement = (CommonTree) cascatedElement.getChild(0);
    if (anyElement.getChildCount() <= 1) {
      return cascatedElement;
    }
    CommonTree tableName = (CommonTree) anyElement.getChild(0);
    CommonTree columnName = (CommonTree) anyElement.getChild(1);
    String tableAlias = this.tableAliasMap.get(tableName.getText());
    if (tableAlias != null) {
      tableName.getToken().setText(tableAlias);
      Map<String, String> columnMap = this.columnAliasMap.get(tableAlias);
      if (columnMap != null) {
        String columnAlias = columnMap.get(columnName.getText());
        if (columnAlias != null) {
          columnName.getToken().setText(columnAlias);
        }
      }
    }
    return cascatedElement;
  }

  /**
   * only for bottomSelect
   *
   * @param select
   */
  void processSelectAsterisk(CommonTree select) {
    for (int i = 0; i < select.getChildCount(); i++) {
      CommonTree node = (CommonTree) select.getChild(i);
      if (node.getType() == PantheraParser_PLSQLParser.ASTERISK) {
        node.getToken().setType(PantheraParser_PLSQLParser.SELECT_LIST);
        node.getToken().setText("SELECT_LIST");
      }
    }
  }

  void rebuildSelectListByFilter(boolean isLeftJoin, boolean needGroup, CommonTree joinSubAlias,
      CommonTree topAlias) throws SqlXlateException {
    List<Map<Boolean, List<CommonTree>>> joinKeys = this.getFilterkey();
    Set<String> selectKeySet = new HashSet<String>();
    for (int i = 0; i < joinKeys.size(); i++) {
      List<CommonTree> bottomKeys = joinKeys.get(i).get(false);
      List<CommonTree> topKeys = joinKeys.get(i).get(true);
      if (bottomKeys != null) {
        for (CommonTree bottomKey : bottomKeys) {
          String selectKey;
          // FIXME when bottomKey is not CASCATED_ELEMENT
          selectKey = bottomKey.getChild(0).getChildCount() == 2 ? bottomKey.getChild(0)
              .getChild(1).getText() : bottomKey.getChild(0).getChild(0).getText();
          if (needGroup && !selectKeySet.contains(selectKey)) {
            selectKeySet.add(selectKey);
            // group
            this.buildGroup(FilterBlockUtil.cloneTree(bottomKey));
          }
          // add select item
          CommonTree joinKeyAlias = this.addSelectItem((CommonTree) bottomSelect
              .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST), FilterBlockUtil
              .cloneTree(bottomKey));
          // modify filter to alias
          CommonTree anyElement = FilterBlockUtil.findOnlyNode(bottomKey,
              PantheraExpParser.ANY_ELEMENT);
          if (anyElement.getChildCount() == 2) {
            ((CommonTree) anyElement.getChild(0)).getToken().setText(
                joinSubAlias.getChild(0).getText());
            ((CommonTree) anyElement.getChild(1)).getToken().setText(
                joinKeyAlias.getChild(0).getText());
          } else {
            ((CommonTree) anyElement.getChild(0)).getToken().setText(
                joinKeyAlias.getChild(0).getText());
          }
          if (isLeftJoin && topKeys != null) {
            CommonTree isNull = FilterBlockUtil.createSqlASTNode(
                PantheraParser_PLSQLParser.IS_NULL, "IS_NULL");
            isNull.addChild(FilterBlockUtil.cloneTree(bottomKey));
            CommonTree and = FilterBlockUtil.createSqlASTNode(
                PantheraParser_PLSQLParser.SQL92_RESERVED_AND, "and");
            and.addChild(fb.getASTNode());
            and.addChild(isNull);
            if (context.getBallFromBasket(isNull) != null) {
              throw new SqlXlateException("fatal error: translate context conflict.");
            }
            // for CrossjoinTransformer which should not optimise isNull node in WHERE.
            context.putBallToBasket(isNull, true);
            this.fb.setASTNode(and);
          }
          rebuildWhereKey4rebuildSelectListByFilter(bottomKey, anyElement);
        }
      }

      if (topKeys != null && topAlias != null) {
        CommonTree topKey = topKeys.get(0);// can't more than one topkey.

        // add select item
        CommonTree joinKeyAlias = this.addSelectItem((CommonTree) topSelect
            .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST), FilterBlockUtil
            .cloneTree(topKey));
        // modify filter to alias
        CommonTree anyElement = FilterBlockUtil.findOnlyNode(topKey, PantheraExpParser.ANY_ELEMENT);
        if (anyElement.getChildCount() == 2) {
          ((CommonTree) anyElement.getChild(0)).getToken().setText(topAlias.getChild(0).getText());
          ((CommonTree) anyElement.getChild(1)).getToken().setText(
              joinKeyAlias.getChild(0).getText());
        } else {
          ((CommonTree) anyElement.getChild(0)).getToken().setText(
              joinKeyAlias.getChild(0).getText());
        }
        rebuildWhereKey4rebuildSelectListByFilter(topKey, anyElement);
      }
    }
  }

  private void rebuildWhereKey4rebuildSelectListByFilter(CommonTree bottomKey, CommonTree anyElement) {
    if (bottomKey.getType() != PantheraExpParser.CASCATED_ELEMENT) {
      CommonTree cascatedElement = (CommonTree) anyElement.getParent();
      int index = bottomKey.childIndex;
      CommonTree whereOp = (CommonTree) bottomKey.getParent();
      whereOp.deleteChild(index);
      SqlXlateUtil.addCommonTreeChild(whereOp, index, cascatedElement);
    }
  }

  CommonTree buildWhereByFB(CommonTree subQCondition, CommonTree compareKeyAlias1,
      CommonTree compareKeyAlias2) {
    FilterBlockUtil.deleteBranch(bottomSelect, PantheraParser_PLSQLParser.SQL92_RESERVED_WHERE);
    CommonTree where = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_WHERE, "where");
    CommonTree logicExpr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.LOGIC_EXPR,
        "LOGIC_EXPR");
    FilterBlockUtil.attachChild(where, logicExpr);
    FilterBlockUtil.attachChild(logicExpr, fb.getASTNode());
    if (compareKeyAlias1 != null && compareKeyAlias2 != null) {
      this.addConditionToWhere(where, FilterBlockUtil.dupNode(subQCondition), this
          .createCascatedElement(FilterBlockUtil.cloneTree((CommonTree) compareKeyAlias1
              .getChild(0))), this.createCascatedElement(FilterBlockUtil
          .cloneTree((CommonTree) compareKeyAlias2.getChild(0))));
    }
    FilterBlockUtil.attachChild(topSelect, where);
    return where;
  }

  void builldSimpleWhere(CommonTree condition) {
    CommonTree where = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_WHERE, "where");
    CommonTree logicExpr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.LOGIC_EXPR,
        "LOGIC_EXPR");
    FilterBlockUtil.attachChild(where, logicExpr);
    FilterBlockUtil.attachChild(logicExpr, condition);
    FilterBlockUtil.attachChild(topSelect, where);
  }

  void rebuildGroupOrder(CommonTree topAlias) {
    CommonTree group = topQuery.getGroup();
    CommonTree order = topQuery.getOrder();
    if (group != null) {
      for (int i = 0; i < group.getChildCount(); i++) {
        CommonTree groupElement = (CommonTree) group.getChild(i);
        CommonTree anyElement = (CommonTree) groupElement.getChild(0).getChild(0).getChild(0);
        this.rebuildAnyElementAlias(topAlias, anyElement);
      }
    }
    if (order != null) {
      CommonTree orderByElements = (CommonTree) order.getChild(0);
      for (int i = 0; i < orderByElements.getChildCount(); i++) {
        CommonTree ct = (CommonTree) orderByElements.getChild(i).getChild(0).getChild(0);
        if (ct.getType() == PantheraParser_PLSQLParser.CASCATED_ELEMENT) {// NOT order by 1,2
          CommonTree anyElement = (CommonTree) ct.getChild(0);
          this.rebuildAnyElementAlias(topAlias, anyElement);
        }
      }
    }
  }

  void rebuildAnyElementAlias(CommonTree topAlias, CommonTree anyElement) {
    CommonTree column;
    if (anyElement.getChildCount() == 2) {
      // ((CommonTree) anyElement.getChild(0)).getToken().setText(topAlias.getChild(0).getText());//
      // TODO
      // remove, needn't table alias.
      anyElement.deleteChild(0);
    }
    column = (CommonTree) anyElement.getChild(0);
    Map<String, String> columnMap = this.columnAliasMap.get(topAlias == null ? "" : topAlias
        .getChild(0).getText());
    String columnAlias = columnMap.get(column.getText());
    if (columnAlias != null) {
      column.getToken().setText(columnAlias);
    }
  }

  /**
   * rebuild order&group with alias when create closing select list
   *
   * @param oldAlias
   * @param newAlias
   */
  void reRebuildGroupOrder(String oldAlias, String newAlias) {

    CommonTree group = topQuery.getGroup();
    CommonTree order = topQuery.getOrder();
    if (group != null) {
      // FIXME should rebuild group alias in query block, use select_list's column replace alias

      // for (int i = 0; i < group.getChildCount(); i++) {
      // CommonTree groupElement = (CommonTree) group.getChild(i);
      // CommonTree anyElement = (CommonTree) groupElement.getChild(0).getChild(0).getChild(0);
      // this.reRebuildAnyElement(oldAlias, newAlias, anyElement);
      // }
    }
    if (order != null) {
      CommonTree orderByElements = (CommonTree) order.getChild(0);
      for (int i = 0; i < orderByElements.getChildCount(); i++) {
        CommonTree orderByElement = (CommonTree) orderByElements.getChild(i);
        CommonTree anyElement = (CommonTree) orderByElement.getChild(0).getChild(0).getChild(0);
        if (anyElement != null && anyElement.getType() == PantheraParser_PLSQLParser.ANY_ELEMENT) {
          this.reRebuildAnyElement(oldAlias, newAlias, anyElement);
        }
      }
    }
  }

  private void reRebuildAnyElement(String oldAlias, String newAlias, CommonTree anyElement) {

    if (anyElement.getChildCount() == 2) {
      anyElement.deleteChild(0);
    }
    CommonTree child = (CommonTree) anyElement.getChild(0);
    if (child.getText().equals(oldAlias)) {
      child.getToken().setText(newAlias);
    }

  }


  /**
   * for intersect
   *
   * @param selectList
   * @param topAlias
   * @param bottomAlias
   * @return
   */
  CommonTree makeOn(CommonTree topSelectList, CommonTree bottomSelectList, CommonTree topAlias,
      CommonTree bottomAlias) {
    CommonTree on = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.SQL92_RESERVED_ON,
        "on");
    CommonTree logicExpr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.LOGIC_EXPR,
        "LOGIC_EXPR");
    FilterBlockUtil.attachChild(on, logicExpr);

    for (int i = 0; i < topSelectList.getChildCount(); i++) {
      CommonTree topAliasId = (CommonTree) topSelectList.getChild(i).getChild(1).getChild(0);
      CommonTree bottomAliasId = (CommonTree) bottomSelectList.getChild(i).getChild(1).getChild(0);
      CommonTree topColumn = FilterBlockUtil.dupNode(topAliasId);
      CommonTree bottomColumn = FilterBlockUtil.dupNode(bottomAliasId);
      CommonTree left = this.createCascatedElement((CommonTree) topAlias.getChild(0), topColumn);
      CommonTree right = this.createCascatedElement((CommonTree) bottomAlias.getChild(0),
          bottomColumn);
      CommonTree equals = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.EQUALS_OP,
          "=");
      FilterBlockUtil.attachChild(equals, left);
      FilterBlockUtil.attachChild(equals, right);
      if (logicExpr.getChildCount() > 0) {
        CommonTree origin = (CommonTree) logicExpr.deleteChild(0);
        CommonTree and = FilterBlockUtil.createSqlASTNode(
            PantheraParser_PLSQLParser.SQL92_RESERVED_AND, "and");
        FilterBlockUtil.attachChild(and, origin);
        FilterBlockUtil.attachChild(and, equals);
        FilterBlockUtil.attachChild(logicExpr, and);
      } else {
        FilterBlockUtil.attachChild(logicExpr, equals);
      }

    }
    return on;
  }

  CommonTree buildNotIn4Minus(CommonTree minuendSelect, CommonTree substrahendSelect)
      throws SqlXlateException {
    CommonTree notIn = FilterBlockUtil
        .createSqlASTNode(PantheraParser_PLSQLParser.NOT_IN, "NOT_IN");
    FilterBlockUtil.attachChild(notIn, this.buildNotInParameter(minuendSelect));
    FilterBlockUtil.attachChild(notIn, substrahendSelect);
    return notIn;
  }

  private CommonTree buildNotInParameter(CommonTree select) throws SqlXlateException {
    CommonTree selectList = (CommonTree) select
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST);
    if (selectList == null) {
      throw new SqlXlateException("unsupport select * from subquery.");
    }
    if (selectList.getChildCount() == 1) {
      return FilterBlockUtil.cloneTree((CommonTree) selectList.getChild(0).getChild(0).getChild(0));
    }
    CommonTree vectorExpr = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.VECTOR_EXPR, "VECTOR_EXPR");
    for (int i = 0; i < selectList.getChildCount(); i++) {

      CommonTree expr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.EXPR, "EXPR");
      FilterBlockUtil.attachChild(vectorExpr, expr);
      FilterBlockUtil.attachChild(expr, FilterBlockUtil.cloneTree((CommonTree) selectList.getChild(
          i).getChild(0).getChild(0)));
    }
    return vectorExpr;
  }

  /**
   * add SELECT_ITEM for subq IN
   *
   * @param select
   * @param subq
   * @return
   * @throws SqlXlateException
   */
  List<CommonTree> addSelectItems4In(CommonTree select, CommonTree subq) throws SqlXlateException {
    CommonTree left = (CommonTree) subq.getChild(0);
    List<CommonTree> result = new ArrayList<CommonTree>();
    if (left.getType() == PantheraParser_PLSQLParser.CASCATED_ELEMENT) {
      CommonTree compareElementAlias = this.addSelectItem((CommonTree) select
          .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST), FilterBlockUtil
          .cloneTree(left));
      result.add(compareElementAlias);
      return result;
    }
    if (left.getType() == PantheraParser_PLSQLParser.VECTOR_EXPR) {
      for (int i = 0; i < left.getChildCount(); i++) {
        CommonTree compareElementAlias = this.addSelectItem((CommonTree) select
            .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST), FilterBlockUtil
            .cloneTree((CommonTree) left.getChild(i).getChild(0)));
        result.add(compareElementAlias);
      }
      return result;
    }
    return null;
  }

  /**
   * rebuild column alias to sequence alias after unnested subquery
   *
   * @param select
   */
  private void rebuildColumnAlias(CommonTree select) {
    CommonTree selectList = (CommonTree) select
        .getFirstChildWithType(PantheraParser_PLSQLParser.SELECT_LIST);
    int count = 0;
    if (selectList == null) {
      return;
    }
    for (int i = 0; i < selectList.getChildCount(); i++) {
      CommonTree selectItem = (CommonTree) selectList.getChild(i);
      if (selectItem.getChildCount() == 2) {
        CommonTree aliasName = (CommonTree) selectItem.getChild(1).getChild(0);
        String oldColAlias = aliasName.getText();
        if ("panthera".equals(oldColAlias.split("_")[0])) {
          String newColAlias = "panthera_col_" + count++;
          aliasName.getToken().setText(newColAlias);
          reRebuildGroupOrder(oldColAlias, newColAlias);
        }
      }
    }
  }

  CommonTree createCountAsteriskSelectList() {
    CommonTree selectList = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SELECT_LIST, "SELECT_LIST");
    CommonTree selectItem = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SELECT_ITEM, "SELECT_ITEM");
    selectList.addChild(selectItem);
    CommonTree expr = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.EXPR, "EXPR");
    selectItem.addChild(expr);
    CommonTree standardFunction = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.STANDARD_FUNCTION, "STANDARD_FUNCTION");
    expr.addChild(standardFunction);
    CommonTree count = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.COUNT_VK,
        "count");
    standardFunction.addChild(count);
    CommonTree asterisk = FilterBlockUtil
        .createSqlASTNode(PantheraParser_PLSQLParser.ASTERISK, "*");
    count.addChild(asterisk);
    CommonTree alias = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.ALIAS, "ALIAS");
    selectItem.addChild(alias);
    CommonTree aliasName = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.ID,
        "panthera_col_0");
    alias.addChild(aliasName);
    return selectList;
  }

  CommonTree reCreateBottomSelect(CommonTree tableRefElement, CommonTree selectList) {
    CommonTree select = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_SELECT, "select");
    CommonTree from = FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.SQL92_RESERVED_FROM, "from");
    select.addChild(from);
    CommonTree tableRef = FilterBlockUtil.createSqlASTNode(PantheraParser_PLSQLParser.TABLE_REF,
        "TABLE_REF");
    from.addChild(tableRef);
    tableRef.addChild(tableRefElement);
    select.addChild(selectList);
    return select;
  }

  void reBuildNotExist4UCWhere(CommonTree select, CommonTree tabAlias) throws SqlXlateException {
    CommonTree where = (CommonTree) select
        .getFirstChildWithType(PantheraParser_PLSQLParser.SQL92_RESERVED_WHERE);
    if (where != null) {
      throw new SqlXlateException("Uncorrelated NOT EXISTS, no WHERE.");
    }
    CommonTree cascatedElement = this.createCascatedElement(tabAlias, FilterBlockUtil
        .createSqlASTNode(PantheraParser_PLSQLParser.ID, "panthera_col_0"));
    CommonTree zero = SqlXlateUtil.newSqlASTNode(PantheraExpParser.UNSIGNED_INTEGER, "0");
    select.addChild(this.buildWhere(FilterBlockUtil.createSqlASTNode(
        PantheraParser_PLSQLParser.EQUALS_OP, "="), cascatedElement, zero));
  }
}
