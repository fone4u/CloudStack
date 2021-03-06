(function($, cloudStack) {
  /**
   * Make <ul> of tree items
   */
  var makeTreeList = function(args) {
    var $treeList = $('<ul>');

    args.dataProvider({
      context: $.extend(args.context, {
        parentDomain: args.parent
      }),
      response: {
        success: function(successArgs) {
          $(successArgs.data).each(function() {
            $('<li>')
              .data('tree-view-item-id', this.id)
              .data('tree-view-item-obj', this)
              .append(
                $('<div>')
                  .addClass('expand')
              )
              .append(
                $('<div>').addClass('name')
                  .html(this.name)
              )
              .appendTo($treeList);
          });
        }        
      }
    });

    return $treeList;
  };

  /**
   * Define an infinite 'tree' list
   */
  $.fn.treeView = function(args) {
    var $treeView = $('<div>')
          .appendTo(this)
          .addClass('view tree-view');
    var $toolbar = $('<div>')
          .addClass('toolbar')
          .append(
            $('<div>')
              .addClass('text-search')
              .append(
                $('<div>')
                  .addClass('search-bar')
                  .append(
                    $('<input>').attr('type', 'text')
                  )
              )
              .append(
                $('<div>').addClass('button search')
              )
          )
          .prependTo($treeView);
    var treeViewArgs = args.treeView;
    var $browser = args.$browser;

    makeTreeList({
      parent: null,
      dataProvider: treeViewArgs.dataProvider,
      context: args.context
    }).appendTo($treeView);

    setTimeout(function() {
      $treeView.find('li:first div.name').click();
    }, 100);

    this.click(function(event) {
      var $target = $(event.target);
      var $li = $target.closest('li');

      if ($target.is('li div.expand') && $li.data('tree-view-item-obj')) {
        if ($li.find('ul').size()) {
          $li.find('ul').remove();
          $li.removeClass('expanded');

          return false;
        }

        makeTreeList({
          parent: $li.data('tree-view-item-obj'),
          dataProvider: treeViewArgs.dataProvider
        }).appendTo($li);
        $li.addClass('expanded');
        
        return false;
      }

      if ($target.is('li .name')) {
        $treeView.find('li .name').removeClass('selected');
        $target.addClass('selected');
        var $panel = $browser.cloudBrowser('addPanel', {
          title: $target.html(),
          data: '',
          parent: $treeView.closest('div.panel')
        });

        $panel.detailView($.extend(treeViewArgs.detailView, {
          id: $li.data('tree-view-item-id'),
          $browser: $browser,
          context: { domains: [ $li.data('tree-view-item-obj') ] }
        }));
      }

      return true;
    });

    // Action events
    $(window).bind('cloudstack.view-item-action', function(event, data) {
      var actionName = data.actionName;
      var $li = $treeView.find('li').filter(function() {
        return $(this).data('tree-view-item-id') == data.id;
      });

      if (actionName == 'destroy') {
        $li.animate({ opacity: 0.5 });
        $li.bind('click', function() { return false; });
      }
    }); 

    return this;
  };
})(jQuery, cloudStack);
